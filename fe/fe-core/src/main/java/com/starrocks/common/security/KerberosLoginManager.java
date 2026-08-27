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

import com.starrocks.common.Config;
import com.starrocks.common.InvalidConfException;
import com.starrocks.common.util.Daemon;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

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
