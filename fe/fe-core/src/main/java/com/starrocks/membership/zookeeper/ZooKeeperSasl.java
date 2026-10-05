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

package com.starrocks.membership.zookeeper;

import com.google.common.base.Strings;
import com.sun.security.auth.module.Krb5LoginModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;

/**
 * Gives the ZooKeeper client a Kerberos login from the FE keytab: an in-memory JAAS entry for the
 * client login context, so an ensemble that requires SASL authenticates the FE without a JAAS file
 * of its own. An operator-provided {@code java.security.auth.login.config} always wins.
 */
public final class ZooKeeperSasl {
    private static final Logger LOG = LogManager.getLogger(ZooKeeperSasl.class);

    private static final String JAAS_FILE_PROPERTY = "java.security.auth.login.config";
    private static final String CLIENT_CONTEXT_PROPERTY = "zookeeper.sasl.clientconfig";
    private static final String DEFAULT_CLIENT_CONTEXT = "Client";

    private ZooKeeperSasl() {
    }

    public static void installClientEntry(String principal, String keytab) {
        if (!Strings.isNullOrEmpty(System.getProperty(JAAS_FILE_PROPERTY))) {
            LOG.info("{} is set, keeping the operator JAAS configuration for the zookeeper client", JAAS_FILE_PROPERTY);
            return;
        }
        if (Configuration.getConfiguration() instanceof KeytabClientConfiguration) {
            return;
        }
        Configuration prior = Configuration.getConfiguration();
        Configuration.setConfiguration(new KeytabClientConfiguration(prior, principal, keytab));
        LOG.info("zookeeper client SASL logs in from keytab {} as {}", keytab, principal);
    }

    /** JAAS configuration that answers the zookeeper client context from the keytab and delegates the rest. */
    private static final class KeytabClientConfiguration extends Configuration {
        private final Configuration delegate;
        private final String clientContext;
        private final AppConfigurationEntry clientEntry;

        KeytabClientConfiguration(Configuration delegate, String principal, String keytab) {
            this.delegate = delegate;
            this.clientContext = Strings.nullToEmpty(System.getProperty(CLIENT_CONTEXT_PROPERTY))
                    .trim().isEmpty() ? DEFAULT_CLIENT_CONTEXT : System.getProperty(CLIENT_CONTEXT_PROPERTY).trim();
            Map<String, Object> options = new HashMap<>();
            options.put("useKeyTab", "true");
            options.put("keyTab", keytab);
            options.put("principal", principal);
            options.put("doNotPrompt", "true");
            options.put("storeKey", "true");
            options.put("useTicketCache", "false");
            options.put("refreshKrb5Config", "true");
            this.clientEntry = new AppConfigurationEntry(Krb5LoginModule.class.getName(),
                    AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, Collections.unmodifiableMap(options));
        }

        @Override
        public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
            AppConfigurationEntry[] fromDelegate = null;
            if (delegate != null) {
                try {
                    fromDelegate = delegate.getAppConfigurationEntry(name);
                } catch (Exception e) {
                    LOG.warn("JAAS delegate failed to answer entry {}: {}", name, e.getMessage());
                }
            }
            if (fromDelegate != null && fromDelegate.length > 0) {
                return fromDelegate;
            }
            return clientContext.equals(name) ? new AppConfigurationEntry[] {clientEntry} : null;
        }

        @Override
        public void refresh() {
            if (delegate != null) {
                delegate.refresh();
            }
        }
    }
}
