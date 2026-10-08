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
import com.starrocks.common.InvalidConfException;
import com.starrocks.common.util.Daemon;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.Oid;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.InetAddress;
import java.security.PrivilegedExceptionAction;

/**
 * Logs the FE process in to Kerberos from a keytab and keeps the ticket fresh, so that every
 * outgoing Hadoop interaction (HDFS, Hive Metastore, Ranger Admin) authenticates as the service
 * principal without an externally maintained ticket cache.
 * <p>
 * The feature is off unless both {@code kerberos_principal} and {@code kerberos_keytab} are set;
 * when off, nothing here touches the global {@link UserGroupInformation} state.
 */
public class KerberosLoginManager {
    private static final Logger LOG = LogManager.getLogger(KerberosLoginManager.class);

    private static final String AUTHENTICATION_KEY = "hadoop.security.authentication";
    private static final String KERBEROS_AUTHENTICATION = "kerberos";
    private static final String RELOGIN_DAEMON_NAME = "kerberos-relogin";

    private static boolean loggedIn = false;
    private static Daemon reloginDaemon = null;
    private static volatile GSSCredential acceptorCredential = null;

    private KerberosLoginManager() {
    }

    /**
     * Must be called before anything else touches {@link UserGroupInformation}, otherwise Hadoop
     * caches a login user built from the ambient environment.
     */
    public static synchronized void loginIfConfigured() throws InvalidConfException, IOException {
        if (loggedIn) {
            return;
        }

        String principal = Config.kerberos_principal.trim();
        String keytab = Config.kerberos_keytab.trim();

        if (principal.isEmpty() && keytab.isEmpty()) {
            return;
        }
        if (principal.isEmpty() || keytab.isEmpty()) {
            throw new InvalidConfException(
                    "kerberos_principal and kerberos_keytab must both be set to enable Kerberos login");
        }
        File keytabFile = new File(keytab);
        if (!keytabFile.isFile() || !keytabFile.canRead()) {
            throw new InvalidConfException("kerberos_keytab is not a readable file: " + keytab);
        }

        String resolvedPrincipal =
                SecurityUtil.getServerPrincipal(principal, InetAddress.getLocalHost().getCanonicalHostName());

        // Keeps whatever core-site.xml on the classpath provides (auth_to_local rules and such)
        // and only forces the authentication method.
        Configuration conf = new Configuration();
        conf.set(AUTHENTICATION_KEY, KERBEROS_AUTHENTICATION);
        UserGroupInformation.setConfiguration(conf);
        UserGroupInformation.loginUserFromKeytab(resolvedPrincipal, keytab);
        loggedIn = true;

        LOG.info("Kerberos login succeeded, principal: {}, keytab: {}", resolvedPrincipal, keytab);

        reloginDaemon = new ReloginDaemon(Config.kerberos_relogin_check_interval_second * 1000L);
        reloginDaemon.start();
    }

    public static synchronized boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * Cached ACCEPT_ONLY krb5 credential built from the login Subject, used by the
     * auth_gssapi client authentication plugin.
     */
    public static GSSCredential acceptorCredential() throws AuthenticationException {
        GSSCredential cached = acceptorCredential;
        if (cached != null) {
            return cached;
        }
        synchronized (KerberosLoginManager.class) {
            if (acceptorCredential == null) {
                if (!isLoggedIn()) {
                    throw new AuthenticationException("kerberos login is not enabled on this FE");
                }
                try {
                    acceptorCredential = UserGroupInformation.getLoginUser().doAs(
                            (PrivilegedExceptionAction<GSSCredential>) () -> GSSManager.getInstance().createCredential(
                                    null, GSSCredential.DEFAULT_LIFETIME, new Oid("1.2.840.113554.1.2.2"),
                                    GSSCredential.ACCEPT_ONLY));
                } catch (IOException | InterruptedException | RuntimeException e) {
                    // hadoop doAs rethrows undeclared checked exceptions (e.g. GSSException)
                    // wrapped in UndeclaredThrowableException
                    Throwable cause = (e instanceof UndeclaredThrowableException && e.getCause() != null)
                            ? e.getCause() : e;
                    throw new AuthenticationException(
                            "failed to create the kerberos acceptor credential: " + cause.getMessage());
                }
            }
            return acceptorCredential;
        }
    }

    /**
     * Test-only: drops the credential cache, the login state and the relogin daemon, and
     * resets UserGroupInformation — without the UGI reset the JVM caches the login user
     * across tests.
     */
    public static void resetForTest() {
        synchronized (KerberosLoginManager.class) {
            acceptorCredential = null;
            loggedIn = false;
            if (reloginDaemon != null) {
                reloginDaemon.setStop();
                reloginDaemon = null;
            }
            UserGroupInformation.reset();
        }
    }

    /**
     * The SPN this FE accepts GSSAPI clients on: {@code kerberos_principal} with _HOST
     * resolved, the same resolution the login performs. Empty when Kerberos login is
     * not configured.
     */
    public static String servicePrincipal() throws IOException {
        String principal = Config.kerberos_principal.trim();
        if (principal.isEmpty()) {
            return "";
        }
        return SecurityUtil.getServerPrincipal(principal, InetAddress.getLocalHost().getCanonicalHostName());
    }

    private static class ReloginDaemon extends Daemon {
        ReloginDaemon(long intervalMs) {
            super(RELOGIN_DAEMON_NAME, intervalMs);
        }

        @Override
        protected void runOneCycle() {
            try {
                UserGroupInformation.getLoginUser().checkTGTAndReloginFromKeytab();
            } catch (IOException e) {
                LOG.warn("failed to renew the Kerberos ticket, keeping the current one", e);
            }
        }
    }
}
