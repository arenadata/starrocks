// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.authentication;

import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.security.KerberosLoginManager;
import com.starrocks.mysql.MysqlAuthPacket;
import com.starrocks.mysql.MysqlCapability;
import com.starrocks.mysql.MysqlChannel;
import com.starrocks.mysql.MysqlProto;
import com.starrocks.mysql.MysqlSerializer;
import com.starrocks.mysql.NegotiateState;
import com.starrocks.mysql.privilege.AuthPlugin;
import com.starrocks.persist.EditLog;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.ast.CreateUserStmt;
import com.starrocks.sql.parser.SqlParser;
import com.sun.security.auth.module.Krb5LoginModule;
import mockit.Mock;
import mockit.MockUp;
import org.apache.hadoop.minikdc.MiniKdc;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.security.auth.Subject;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

public class KerberosAuthenticationProviderTest {
    private static final String REALM = "EXAMPLE.COM";
    private static final String SERVICE_PRINCIPAL = "starrocks/localhost@EXAMPLE.COM";

    private static MiniKdc kdc;
    private static File keytabFile;
    private static String savedPrincipal;
    private static String savedKeytab;

    private final List<byte[]> serverSent = new ArrayList<>();

    @BeforeAll
    public static void startKdcAndLogin() throws Exception {
        File workDir = Files.createTempDirectory(Paths.get("target"), "minikdc").toFile();
        System.setProperty("java.security.krb5.conf", new File(workDir, "krb5.conf").getAbsolutePath());
        Properties conf = MiniKdc.createConf();
        conf.setProperty(MiniKdc.ORG_NAME, "EXAMPLE");
        conf.setProperty(MiniKdc.ORG_DOMAIN, "COM");
        kdc = new MiniKdc(conf, workDir);
        kdc.start();

        keytabFile = new File(workDir, "kerberos.keytab");
        kdc.createPrincipal(keytabFile, "starrocks/localhost", "alice", "bob");

        savedPrincipal = Config.kerberos_principal;
        savedKeytab = Config.kerberos_keytab;
        Config.kerberos_principal = SERVICE_PRINCIPAL;
        Config.kerberos_keytab = keytabFile.getAbsolutePath();
        KerberosLoginManager.loginIfConfigured();
    }

    @AfterAll
    public static void stopKdcAndRestore() throws Exception {
        Config.kerberos_principal = savedPrincipal;
        Config.kerberos_keytab = savedKeytab;
        KerberosLoginManager.resetForTest();
        if (kdc != null) {
            kdc.stop();
        }
    }

    @Test
    public void testMatchesRequestedUser() {
        Assertions.assertTrue(KerberosAuthenticationProvider.matchesRequestedUser(
                "alice", null, "alice@EXAMPLE.COM"));
        Assertions.assertTrue(KerberosAuthenticationProvider.matchesRequestedUser(
                "alice@EXAMPLE.COM", null, "alice@EXAMPLE.COM"));
        Assertions.assertTrue(KerberosAuthenticationProvider.matchesRequestedUser(
                "alice", "alice@EXAMPLE.COM", "alice@EXAMPLE.COM"));
        // prefix without the @ boundary must not match
        Assertions.assertFalse(KerberosAuthenticationProvider.matchesRequestedUser(
                "carol", null, "carolmark@EXAMPLE.COM"));
        // the realm is not part of the short-name match — MariaDB compares only up to the '@'
        Assertions.assertTrue(KerberosAuthenticationProvider.matchesRequestedUser(
                "alice", null, "alice@EVIL.COM"));
        Assertions.assertFalse(KerberosAuthenticationProvider.matchesRequestedUser(
                "alice", "bob@EXAMPLE.COM", "alice@EXAMPLE.COM"));
        Assertions.assertFalse(KerberosAuthenticationProvider.matchesRequestedUser(
                "alice", null, "bob@EXAMPLE.COM"));
        Assertions.assertFalse(KerberosAuthenticationProvider.matchesRequestedUser(
                "alice", null, null));
    }

    @Test
    public void testE2eMutualAuth() throws Exception {
        GssClient alice = new GssClient("alice", true);
        ConnectContext ctx = new ConnectContext();
        MysqlProto.NegotiateResult result = drive(
                "create user alice identified with kerberos", "alice", alice.firstToken(), ctx);
        Assertions.assertEquals(NegotiateState.OK, result.state(), ctx.getState().getErrorMessage());
        Assertions.assertEquals(1, serverSent.size(), "mutual auth expects exactly the AP-REP");
        Assertions.assertTrue(alice.consumeServerToken(clientViewOfServerPacket(serverSent.get(0))),
                "the server packet must complete the initiator context");

        // twin without mutual auth (libmariadb-style client): the server must stay silent
        GssClient plain = new GssClient("alice", false);
        ctx = new ConnectContext();
        result = drive("create user alice identified with kerberos", "alice", plain.firstToken(), ctx);
        Assertions.assertEquals(NegotiateState.OK, result.state(), ctx.getState().getErrorMessage());
        Assertions.assertEquals(0, serverSent.size(), "no AP-REP was requested");
    }

    @Test
    public void testE2eAuthStringFullPrincipal() throws Exception {
        GssClient alice = new GssClient("alice", false);
        ConnectContext ctx = new ConnectContext();
        MysqlProto.NegotiateResult result = drive(
                "create user alice2 identified with kerberos as 'alice@EXAMPLE.COM'",
                "alice2", alice.firstToken(), ctx);
        Assertions.assertEquals(NegotiateState.OK, result.state(), ctx.getState().getErrorMessage());
    }

    @Test
    public void testE2eFailClosed() throws Exception {
        // principal 'bob' cannot log in as user 'alice'
        GssClient bob = new GssClient("bob", false);
        ConnectContext ctx = new ConnectContext();
        MysqlProto.NegotiateResult result = drive(
                "create user alice identified with kerberos", "alice", bob.firstToken(), ctx);
        Assertions.assertEquals(NegotiateState.AUTHENTICATION_FAILED, result.state());
        Assertions.assertEquals(ErrorCode.ERR_AUTHENTICATION_FAIL, ctx.getState().getErrorCode());

        // garbage first token
        ctx = new ConnectContext();
        result = drive("create user alice identified with kerberos", "alice", new byte[32], ctx);
        Assertions.assertEquals(NegotiateState.AUTHENTICATION_FAILED, result.state());
        Assertions.assertEquals(ErrorCode.ERR_AUTHENTICATION_FAIL, ctx.getState().getErrorCode());

        // FE not logged in: must fail closed, not fall through to another auth method
        KerberosLoginManager.resetForTest();
        GssClient alice = new GssClient("alice", false);
        ctx = new ConnectContext();
        result = drive("create user alice identified with kerberos", "alice", alice.firstToken(), ctx);
        Assertions.assertEquals(NegotiateState.AUTHENTICATION_FAILED, result.state());
        Assertions.assertEquals(ErrorCode.ERR_AUTHENTICATION_FAIL, ctx.getState().getErrorCode());
        KerberosLoginManager.loginIfConfigured();
    }

    /**
     * Runs the full native auth path: creates the user, mocks the mysql channel (recording
     * server payloads, returning null for further client packets — krb5 never sends a
     * second one) and calls MysqlProto.authenticate.
     */
    private MysqlProto.NegotiateResult drive(String createUserSql, String connectUser, byte[] token,
            ConnectContext ctx) throws Exception {
        serverSent.clear();
        new MockUp<MysqlChannel>() {
            @Mock
            public void sendAndFlush(ByteBuffer packet) throws IOException {
                byte[] copy = new byte[packet.remaining()];
                packet.get(copy);
                serverSent.add(copy);
            }

            @Mock
            public ByteBuffer fetchOnePacket() throws IOException {
                return null;
            }
        };
        EditLog editLog = spy(new EditLog(null));
        doNothing().when(editLog).logEdit(anyShort(), any());
        GlobalStateMgr.getCurrentState().setEditLog(editLog);

        AuthenticationMgr authenticationMgr = new AuthenticationMgr();
        GlobalStateMgr.getCurrentState().setAuthenticationMgr(authenticationMgr);
        CreateUserStmt createUserStmt = (CreateUserStmt) SqlParser.parse(createUserSql, 32).get(0);
        Analyzer.analyze(createUserStmt, new ConnectContext());
        authenticationMgr.createUser(createUserStmt);

        return MysqlProto.authenticate(ctx, buildGssPacket(connectUser, token));
    }

    /** Same shape as MysqlAuthPacketTest.buildPacket but with a length-encoded auth response. */
    private static MysqlAuthPacket buildGssPacket(String user, byte[] token) {
        MysqlSerializer serializer = MysqlSerializer.newInstance();
        serializer.writeInt4(MysqlCapability.DEFAULT_CAPABILITY.getFlags());
        serializer.writeInt4(1024000);
        serializer.writeInt1(33);
        serializer.writeBytes(new byte[23]);
        serializer.writeNulTerminateString(user);
        serializer.writeVInt(token.length);
        serializer.writeBytes(token);
        serializer.writeNulTerminateString("");
        serializer.writeNulTerminateString(AuthPlugin.Client.AUTH_GSSAPI_CLIENT.toString());

        MysqlAuthPacket packet = new MysqlAuthPacket();
        Assertions.assertTrue(packet.readFrom(serializer.toByteBuffer()));
        return packet;
    }

    private static byte[] clientViewOfServerPacket(byte[] packet) {
        return packet.length > 0 && packet[0] == 0x01
                ? Arrays.copyOfRange(packet, 1, packet.length) : packet;
    }

    /** Real JGSS initiator logged in from the same keytab as the acceptor's clients. */
    private static final class GssClient {
        private final Subject subject;
        private final GSSContext context;

        GssClient(String principal, boolean mutual) throws Exception {
            Krb5LoginModule krb5 = new Krb5LoginModule();
            subject = new Subject();
            Map<String, Object> options = new HashMap<>();
            options.put("principal", principal + "@" + REALM);
            options.put("useKeyTab", "true");
            options.put("keyTab", keytabFile.getAbsolutePath());
            options.put("storeKey", "true");
            options.put("doNotPrompt", "true");
            options.put("refreshKrb5Config", "true");
            options.put("isInitiator", "true");
            krb5.initialize(subject, null, null, options);
            Assertions.assertTrue(krb5.login() && krb5.commit(), "client login failed for " + principal);

            context = Subject.doAs(subject, (PrivilegedExceptionAction<GSSContext>) () -> {
                GSSContext gss = GSSManager.getInstance().createContext(
                        GSSManager.getInstance().createName(SERVICE_PRINCIPAL, GSSName.NT_USER_NAME),
                        new Oid("1.2.840.113554.1.2.2"), null, GSSContext.DEFAULT_LIFETIME);
                gss.requestMutualAuth(mutual);
                return gss;
            });
        }

        byte[] firstToken() throws Exception {
            return Subject.doAs(subject,
                    (PrivilegedExceptionAction<byte[]>) () -> context.initSecContext(new byte[0], 0, 0));
        }

        boolean consumeServerToken(byte[] token) throws Exception {
            return Subject.doAs(subject, (PrivilegedExceptionAction<Boolean>) () -> {
                context.initSecContext(token, 0, token.length);
                return context.isEstablished();
            });
        }
    }
}
