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

import com.starrocks.common.ErrorCode;
import com.starrocks.common.security.KerberosLoginManager;
import com.starrocks.mysql.MysqlCodec;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.UserIdentity;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * MariaDB auth_gssapi dialect: the switch packet names the client plugin
 * "auth_gssapi_client" and carries "SPN NUL mech" as plugin data; the auth response
 * holds the first raw GSS token, and further tokens are exchanged over the mysql
 * channel until the context is established.
 */
public class KerberosAuthenticationProvider implements AuthenticationProvider {
    private static final int MAX_GSS_ROUNDS = 10;

    private final String authString;

    public KerberosAuthenticationProvider(String authString) {
        this.authString = authString;
    }

    @Override
    public byte[] authSwitchRequestPacket(ConnectContext context, String user, String host)
            throws AuthenticationException {
        String spn;
        try {
            spn = KerberosLoginManager.servicePrincipal();
        } catch (IOException e) {
            throw new AuthenticationException(
                    "failed to resolve the kerberos service principal: " + e.getMessage());
        }
        if (spn.isEmpty()) {
            throw new AuthenticationException("kerberos_principal is not configured on this FE");
        }
        return GssWireCodec.authSwitchData(spn, "");
    }

    @Override
    public void authenticate(ConnectContext context, UserIdentity userIdentity, byte[] authResponse)
            throws AuthenticationException {
        if (!KerberosLoginManager.isLoggedIn()) {
            throw new AuthenticationException("kerberos login is not enabled on this FE");
        }
        String srcPrincipal = acceptGssTokens(context, authResponse);
        String requestedUser = userIdentity.getUser();
        if (!matchesRequestedUser(requestedUser, authString, srcPrincipal)) {
            throw new AuthenticationException(ErrorCode.ERR_AUTHENTICATION_FAIL, requestedUser, "YES");
        }
    }

    /**
     * MariaDB auth_gssapi semantics: a non-empty authString pins the full principal;
     * otherwise the requested user matches the GSS source name exactly or up to the
     * realm separator.
     */
    static boolean matchesRequestedUser(String requestedUser, String authString, String srcPrincipal) {
        if (srcPrincipal == null) {
            return false;
        }
        if (authString != null && !authString.isEmpty()) {
            return srcPrincipal.equals(authString);
        }
        return srcPrincipal.equals(requestedUser) || srcPrincipal.startsWith(requestedUser + "@");
    }

    private String acceptGssTokens(ConnectContext context, byte[] firstToken)
            throws AuthenticationException {
        if (firstToken == null || firstToken.length == 0) {
            throw new AuthenticationException("empty kerberos token");
        }
        GSSContext gss = null;
        try {
            gss = GSSManager.getInstance().createContext(KerberosLoginManager.acceptorCredential());
            byte[] token = firstToken;
            for (int round = 0; ; round++) {
                if (round >= MAX_GSS_ROUNDS) {
                    throw new AuthenticationException(
                            "kerberos handshake did not complete in " + MAX_GSS_ROUNDS + " rounds");
                }
                byte[] output = gss.acceptSecContext(token, 0, token.length);
                if (output != null && output.length > 0) {
                    // tokens whose first byte collides with OK/ERR/AuthSwitch headers are 0x01-escaped
                    context.getMysqlChannel()
                            .sendAndFlush(ByteBuffer.wrap(GssWireCodec.escapeServerToken(output)));
                }
                if (gss.isEstablished()) {
                    GSSName srcName = gss.getSrcName();
                    return srcName == null ? null : srcName.toString();
                }
                ByteBuffer next = context.getMysqlChannel().fetchOnePacket();
                if (next == null) {
                    throw new AuthenticationException(
                            "client closed the connection during the kerberos handshake");
                }
                token = MysqlCodec.readEofString(next);
            }
        } catch (GSSException e) {
            throw new AuthenticationException("kerberos handshake failed: " + e.getMessage());
        } catch (IOException e) {
            throw new AuthenticationException("kerberos handshake IO failed: " + e.getMessage());
        } finally {
            if (gss != null) {
                try {
                    gss.dispose();
                } catch (GSSException ignored) {
                    // context is per-connection; nothing to salvage
                }
            }
        }
    }
}
