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

package com.starrocks.common.security;

import com.starrocks.authentication.AuthenticationException;
import com.starrocks.common.Config;
import mockit.Mock;
import mockit.MockUp;
import org.apache.hadoop.minikdc.MiniKdc;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import javax.security.auth.Subject;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KerberosLoginManagerKdcTest {
    private static final String PRINCIPAL = "starrocks/localhost@EXAMPLE.COM";

    private static MiniKdc kdc;
    private static File keytabFile;
    private static String savedPrincipal;
    private static String savedKeytab;

    @BeforeAll
    public static void startKdc() throws Exception {
        File workDir = Files.createTempDirectory(Paths.get("target"), "minikdc").toFile();
        System.setProperty("java.security.krb5.conf", new File(workDir, "krb5.conf").getAbsolutePath());
        Properties conf = MiniKdc.createConf();
        // the realm is ORG_NAME + "." + ORG_DOMAIN, so this yields EXAMPLE.COM
        conf.setProperty(MiniKdc.ORG_NAME, "EXAMPLE");
        conf.setProperty(MiniKdc.ORG_DOMAIN, "COM");
        kdc = new MiniKdc(conf, workDir);
        kdc.start();

        keytabFile = new File(workDir, "starrocks.keytab");
        kdc.createPrincipal(keytabFile, "starrocks/localhost", "alice");

        savedPrincipal = Config.kerberos_principal;
        savedKeytab = Config.kerberos_keytab;
        Config.kerberos_principal = PRINCIPAL;
        Config.kerberos_keytab = keytabFile.getAbsolutePath();
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
    @Order(1)
    public void testAcceptorCredentialFailsClosedWithoutLogin() {
        Config.kerberos_principal = "";
        Config.kerberos_keytab = "";
        try {
            Assertions.assertThrows(AuthenticationException.class, KerberosLoginManager::acceptorCredential);
        } finally {
            Config.kerberos_principal = PRINCIPAL;
            Config.kerberos_keytab = keytabFile.getAbsolutePath();
        }
    }

    @Test
    @Order(2)
    public void testLoginYieldsAcceptorCredential() throws Exception {
        KerberosLoginManager.loginIfConfigured();
        Assertions.assertTrue(KerberosLoginManager.isLoggedIn());

        GSSCredential cred = KerberosLoginManager.acceptorCredential();
        Assertions.assertNotNull(cred);
        Assertions.assertTrue((cred.getUsage() & GSSCredential.ACCEPT_ONLY) != 0);
        Assertions.assertSame(cred, KerberosLoginManager.acceptorCredential());
    }

    @Test
    @Order(3)
    public void testAcceptorCredentialWrapsGssFailure() throws Exception {
        // fresh login with an empty credential cache, so credential creation runs again
        KerberosLoginManager.resetForTest();
        KerberosLoginManager.loginIfConfigured();

        new MockUp<Subject>() {
            @Mock
            public static Object doAs(Subject subject, java.security.PrivilegedExceptionAction<?> action)
                    throws java.security.PrivilegedActionException {
                throw new java.security.PrivilegedActionException(new GSSException(GSSException.FAILURE));
            }

            @Mock
            public static Object callAs(Subject subject, java.util.concurrent.Callable<?> action)
                    throws Exception {
                throw new java.security.PrivilegedActionException(new GSSException(GSSException.FAILURE));
            }
        };
        AuthenticationException e = Assertions.assertThrows(AuthenticationException.class,
                KerberosLoginManager::acceptorCredential);
        Assertions.assertTrue(e.getMessage().contains("acceptor credential"), e.getMessage());
    }
}
