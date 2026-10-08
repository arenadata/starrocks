// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.mysql;

import com.starrocks.authentication.AuthenticationException;
import com.starrocks.authentication.AuthenticationMgr;
import com.starrocks.authentication.JWTAuthenticationProvider;
import com.starrocks.authentication.JWTSecurityIntegration;
import com.starrocks.authentication.SecurityIntegration;
import com.starrocks.common.Config;
import com.starrocks.common.security.KerberosLoginManager;
import com.starrocks.mysql.privilege.AuthPlugin;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.Analyzer;
import com.starrocks.sql.ast.CreateUserStmt;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.sql.parser.SqlParser;
import com.starrocks.utframe.UtFrameUtils;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class MysqlAuthPacketTest {
    static ConnectContext ctx;
    private ByteBuffer byteBuffer;

    @BeforeAll
    public static void setUp() throws Exception {
        UtFrameUtils.setUpForPersistTest();
        ctx = UtFrameUtils.initCtxForNewPrivilege(UserIdentity.ROOT);
    }

    @AfterAll
    public static void teardown() throws Exception {
        UtFrameUtils.tearDownForPersisTest();
    }

    @Test
    public void testRead() {
        MysqlSerializer serializer = MysqlSerializer.newInstance();

        // capability
        serializer.writeInt4(MysqlCapability.DEFAULT_CAPABILITY.getFlags());
        // max packet size
        serializer.writeInt4(1024000);
        // character set
        serializer.writeInt1(33);
        // reserved
        serializer.writeBytes(new byte[23]);
        // user name
        serializer.writeNulTerminateString("starrocks-user");
        // plugin data
        serializer.writeInt1(20);
        byte[] buf = new byte[20];
        for (int i = 0; i < 20; ++i) {
            buf[i] = (byte) ('a' + i);
        }
        serializer.writeBytes(buf);
        // database
        serializer.writeNulTerminateString("testDb");

        //plugin
        serializer.writeNulTerminateString("testDb");

        byteBuffer = serializer.toByteBuffer();

        MysqlAuthPacket packet = new MysqlAuthPacket();
        Assertions.assertTrue(packet.readFrom(byteBuffer));
        Assertions.assertEquals("starrocks-user", packet.getUser());
        Assertions.assertEquals("testDb", packet.getDb());
    }

    public static MysqlAuthPacket buildPacket(String user, byte[] password, AuthPlugin.Client authPlugin) {
        MysqlSerializer serializer = MysqlSerializer.newInstance();

        // capability
        serializer.writeInt4(MysqlCapability.DEFAULT_CAPABILITY.getFlags());
        // max packet size
        serializer.writeInt4(1024000);
        // character set
        serializer.writeInt1(33);
        // reserved
        serializer.writeBytes(new byte[23]);
        // user name
        serializer.writeNulTerminateString(user);

        // auth response
        serializer.writeInt1(password.length);
        serializer.writeBytes(password);

        // database
        serializer.writeNulTerminateString("");

        //plugin
        serializer.writeNulTerminateString(authPlugin.toString());

        ByteBuffer byteBuffer = serializer.toByteBuffer();

        MysqlAuthPacket packet = new MysqlAuthPacket();
        packet.readFrom(byteBuffer);

        return packet;
    }

    @Test
    public void testMysqlProtocol() throws Exception {
        new MockUp<MysqlChannel>() {
            @Mock
            public void sendAndFlush(ByteBuffer packet) throws IOException {
                return;
            }

            @Mock
            public ByteBuffer fetchOnePacket() throws IOException {
                return ByteBuffer.wrap(new byte[23]);
            }
        };

        AuthenticationMgr authenticationMgr = new AuthenticationMgr();
        GlobalStateMgr.getCurrentState().setAuthenticationMgr(authenticationMgr);
        CreateUserStmt createUserStmt = (CreateUserStmt) SqlParser
                .parse("create user harbor identified with authentication_jwt", 32).get(0);
        Analyzer.analyze(createUserStmt, ctx);
        authenticationMgr.createUser(createUserStmt);

        byte[] buf = new byte[20];
        for (int i = 0; i < 20; ++i) {
            buf[i] = (byte) ('a' + i);
        }
        MysqlAuthPacket authPacket = buildPacket("harbor", buf, AuthPlugin.Client.MYSQL_NATIVE_PASSWORD);
        ConnectContext context = new ConnectContext();
        MysqlProto.switchAuthPlugin(authPacket, context);
        Assertions.assertEquals("authentication_openid_connect_client", authPacket.getPluginName());

        //test security integration
        Map<String, String> properties = new HashMap<>();
        properties.put(JWTSecurityIntegration.SECURITY_INTEGRATION_PROPERTY_TYPE_KEY, "authentication_jwt");
        properties.put(JWTAuthenticationProvider.JWT_JWKS_URL, "jwks.json");
        properties.put(JWTAuthenticationProvider.JWT_PRINCIPAL_FIELD, "preferred_username");
        properties.put(SecurityIntegration.SECURITY_INTEGRATION_PROPERTY_GROUP_PROVIDER, "file_group_provider");
        properties.put(SecurityIntegration.SECURITY_INTEGRATION_GROUP_ALLOWED_LOGIN, "group1");
        authenticationMgr.createSecurityIntegration("oidc", properties, true);

        Config.authentication_chain = new String[] {"native", "oidc"};
        authPacket = buildPacket("tina", buf, AuthPlugin.Client.MYSQL_NATIVE_PASSWORD);
        MysqlProto.switchAuthPlugin(authPacket, context);
        Assertions.assertEquals("authentication_openid_connect_client", authPacket.getPluginName());
    }

    @Test
    public void testFetchFail() throws Exception {
        new MockUp<MysqlChannel>() {
            @Mock
            public void sendAndFlush(ByteBuffer packet) throws IOException {
                return;
            }

            @Mock
            public ByteBuffer fetchOnePacket() throws IOException {
                return null;
            }
        };

        AuthenticationMgr authenticationMgr = new AuthenticationMgr();
        GlobalStateMgr.getCurrentState().setAuthenticationMgr(authenticationMgr);
        CreateUserStmt createUserStmt = (CreateUserStmt) SqlParser
                .parse("create user harbor identified with authentication_jwt", 32).get(0);
        Analyzer.analyze(createUserStmt, ctx);
        authenticationMgr.createUser(createUserStmt);

        byte[] buf = new byte[20];
        for (int i = 0; i < 20; ++i) {
            buf[i] = (byte) ('a' + i);
        }
        MysqlAuthPacket authPacket = buildPacket("harbor", buf, AuthPlugin.Client.MYSQL_NATIVE_PASSWORD);
        ConnectContext context = new ConnectContext();
        Assertions.assertThrows(AuthenticationException.class, () -> MysqlProto.switchAuthPlugin(authPacket, context));

        authPacket.setPluginName(null);
        MysqlProto.switchAuthPlugin(authPacket, context);
        Assertions.assertNull(authPacket.getPluginName());
    }

    @Test
    public void testAuthPlugin() {
        Assertions.assertEquals("mysql_native_password", AuthPlugin.covertFromServerToClient("mysql_native_password"));
        Assertions.assertEquals("mysql_native_password", AuthPlugin.covertFromServerToClient("MYSQL_NATIVE_PASSWORD"));
        Assertions.assertEquals("mysql_clear_password", AuthPlugin.covertFromServerToClient("authentication_ldap_simple"));
        Assertions.assertEquals("authentication_openid_connect_client",
                AuthPlugin.covertFromServerToClient("authentication_jwt"));
    }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    @Test
    public void testAuthPluginKerberos() {
        Assertions.assertEquals("auth_gssapi_client", AuthPlugin.covertFromServerToClient("KERBEROS"));
        Assertions.assertEquals("auth_gssapi_client", AuthPlugin.covertFromServerToClient("kerberos"));
    }

    @Test
    public void testKerberosSwitchPacketBytes() throws Exception {
        String savedPrincipal = Config.kerberos_principal;
        try {
            Config.kerberos_principal = "starrocks/fe1.example.com@EXAMPLE.COM";
            AtomicReference<byte[]> sent = new AtomicReference<>();
            new MockUp<MysqlChannel>() {
                @Mock
                public void sendAndFlush(ByteBuffer packet) throws IOException {
                    byte[] copy = new byte[packet.remaining()];
                    packet.get(copy);
                    sent.set(copy);
                }

                @Mock
                public ByteBuffer fetchOnePacket() throws IOException {
                    return ByteBuffer.wrap(new byte[] {0x6E, 0x01});
                }
            };

            AuthenticationMgr authenticationMgr = new AuthenticationMgr();
            GlobalStateMgr.getCurrentState().setAuthenticationMgr(authenticationMgr);
            CreateUserStmt createUserStmt = (CreateUserStmt) SqlParser
                    .parse("create user harbor identified with kerberos", 32).get(0);
            Analyzer.analyze(createUserStmt, ctx);
            authenticationMgr.createUser(createUserStmt);

            MysqlAuthPacket authPacket = buildPacket("harbor", new byte[20], AuthPlugin.Client.MYSQL_NATIVE_PASSWORD);
            ConnectContext context = new ConnectContext();
            MysqlProto.switchAuthPlugin(authPacket, context);

            Assertions.assertEquals("auth_gssapi_client", authPacket.getPluginName());
            // full payload: 0xFE | "auth_gssapi_client" 0x00 | SPN 0x00 | mech("") 0x00 — the trailing
            // NUL is appended by MysqlProto after the provider's plugin data
            Assertions.assertArrayEquals(
                    concat(new byte[] {(byte) 0xFE}, "auth_gssapi_client".getBytes(StandardCharsets.UTF_8),
                            new byte[] {0}, "starrocks/fe1.example.com@EXAMPLE.COM".getBytes(StandardCharsets.UTF_8),
                            new byte[] {0, 0}),
                    sent.get());
        } finally {
            Config.kerberos_principal = savedPrincipal;
        }
    }

    @Test
    public void testKerberosSwitchFailsClosedWithoutPrincipal() throws Exception {
        String savedPrincipal = Config.kerberos_principal;
        try {
            Config.kerberos_principal = "";
            List<byte[]> sent = new ArrayList<>();
            new MockUp<MysqlChannel>() {
                @Mock
                public void sendAndFlush(ByteBuffer packet) throws IOException {
                    byte[] copy = new byte[packet.remaining()];
                    packet.get(copy);
                    sent.add(copy);
                }

                @Mock
                public ByteBuffer fetchOnePacket() throws IOException {
                    MysqlSerializer serializer = MysqlSerializer.newInstance();
                    serializer.writeInt4(MysqlCapability.DEFAULT_CAPABILITY.getFlags());
                    serializer.writeInt4(1024000);
                    serializer.writeInt1(33);
                    serializer.writeBytes(new byte[23]);
                    serializer.writeNulTerminateString("harbor");
                    serializer.writeInt1(20);
                    serializer.writeBytes(new byte[20]);
                    serializer.writeNulTerminateString("");
                    serializer.writeNulTerminateString("mysql_native_password");
                    return serializer.toByteBuffer();
                }
            };

            AuthenticationMgr authenticationMgr = new AuthenticationMgr();
            GlobalStateMgr.getCurrentState().setAuthenticationMgr(authenticationMgr);
            CreateUserStmt createUserStmt = (CreateUserStmt) SqlParser
                    .parse("create user harbor identified with kerberos", 32).get(0);
            Analyzer.analyze(createUserStmt, ctx);
            authenticationMgr.createUser(createUserStmt);

            MysqlProto.NegotiateResult result = MysqlProto.negotiate(new ConnectContext());
            Assertions.assertEquals(NegotiateState.READ_AUTH_SWITCH_PKG_FAILED, result.state());
            // handshake + an ERR packet: the client must see a server error, not a bare disconnect
            Assertions.assertEquals(2, sent.size());
            Assertions.assertEquals((byte) 0xFF, sent.get(1)[0]);
        } finally {
            Config.kerberos_principal = savedPrincipal;
        }
    }

    @Test
    public void testServicePrincipalHostExpansion() throws Exception {
        String savedPrincipal = Config.kerberos_principal;
        try {
            Assertions.assertEquals("", KerberosLoginManager.servicePrincipal());
            Config.kerberos_principal = "starrocks/_HOST@EXAMPLE.COM";
            String spn = KerberosLoginManager.servicePrincipal();
            Assertions.assertTrue(spn.startsWith("starrocks/"), spn);
            Assertions.assertTrue(spn.endsWith("@EXAMPLE.COM"), spn);
            Assertions.assertNotEquals("starrocks/_HOST@EXAMPLE.COM", spn);
            Config.kerberos_principal = "starrocks/fe1.example.com@EXAMPLE.COM";
            Assertions.assertEquals("starrocks/fe1.example.com@EXAMPLE.COM", KerberosLoginManager.servicePrincipal());
        } finally {
            Config.kerberos_principal = savedPrincipal;
        }
    }
}