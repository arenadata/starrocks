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

package com.starrocks.connector.hadoop;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

/**
 * Logs the JVM embedded in BE/CN in to Kerberos from a keytab and keeps the ticket fresh.
 * libhdfs and the JNI connectors share this JVM, so they all inherit the login user.
 * Called from C++ at startup, only when both the principal and the keytab are configured.
 */
public class KerberosLogin {
    private static final Logger LOGGER = LoggerFactory.getLogger(KerberosLogin.class);

    private static final String AUTHENTICATION_KEY = "hadoop.security.authentication";
    private static final String KERBEROS_AUTHENTICATION = "kerberos";
    private static final String RELOGIN_THREAD_NAME = "kerberos-relogin";

    private static boolean loggedIn = false;

    private KerberosLogin() {
    }

    /**
     * @throws IOException if the login fails; the caller aborts startup rather than run with
     *                     a half-configured security context
     */
    public static synchronized void login(String principal, String keytab, int reloginIntervalSecond)
            throws IOException {
        if (loggedIn) {
            return;
        }
        if (principal == null || principal.isEmpty() || keytab == null || keytab.isEmpty()) {
            throw new IOException("kerberos_principal and kerberos_keytab must both be set");
        }
        File keytabFile = new File(keytab);
        if (!keytabFile.isFile() || !keytabFile.canRead()) {
            throw new IOException("kerberos_keytab is not a readable file: " + keytab);
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

        LOGGER.info("{} Kerberos login succeeded, principal: {}, keytab: {}",
                HadoopExt.LOGGER_MESSAGE_PREFIX, resolvedPrincipal, keytab);

        startReloginThread(reloginIntervalSecond);
    }

    private static void startReloginThread(int reloginIntervalSecond) {
        long intervalMs = reloginIntervalSecond * 1000L;
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(intervalMs);
                    UserGroupInformation.getLoginUser().checkTGTAndReloginFromKeytab();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable e) {
                    LOGGER.warn("{} failed to renew the Kerberos ticket, keeping the current one",
                            HadoopExt.LOGGER_MESSAGE_PREFIX, e);
                }
            }
        }, RELOGIN_THREAD_NAME);
        thread.setDaemon(true);
        thread.start();
    }
}
