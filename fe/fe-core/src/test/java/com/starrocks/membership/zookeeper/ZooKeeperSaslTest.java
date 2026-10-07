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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;

public class ZooKeeperSaslTest {

    private Configuration priorConfiguration;

    @BeforeEach
    public void rememberState() {
        priorConfiguration = Configuration.getConfiguration();
    }

    @AfterEach
    public void restoreState() {
        System.clearProperty("java.security.auth.login.config");
        Configuration.setConfiguration(priorConfiguration);
    }

    @Test
    public void testClientEntryLogsInFromTheFeKeytab() {
        System.clearProperty("java.security.auth.login.config");
        ZooKeeperSasl.installClientEntry("starrocks/_HOST@EXAMPLE.COM", "/etc/security/keytabs/fe.keytab");

        AppConfigurationEntry[] entries = Configuration.getConfiguration().getAppConfigurationEntry("Client");
        Assertions.assertNotNull(entries);
        Assertions.assertEquals(1, entries.length);
        Assertions.assertEquals("com.sun.security.auth.module.Krb5LoginModule", entries[0].getLoginModuleName());
        Map<String, ?> options = entries[0].getOptions();
        Assertions.assertEquals("starrocks/_HOST@EXAMPLE.COM", options.get("principal"));
        Assertions.assertEquals("/etc/security/keytabs/fe.keytab", options.get("keyTab"));
        Assertions.assertEquals("true", options.get("useKeyTab"));
        Assertions.assertEquals("true", options.get("doNotPrompt"));
        Assertions.assertEquals("true", options.get("storeKey"));
        Assertions.assertEquals("false", options.get("useTicketCache"));

        ZooKeeperSasl.installClientEntry("other@EXAMPLE.COM", "/other.keytab");
        Assertions.assertEquals("starrocks/_HOST@EXAMPLE.COM", entries[0].getOptions().get("principal"),
                "a second install must not wrap the configuration again");
    }

    @Test
    public void testOperatorJaasFileWins() {
        System.setProperty("java.security.auth.login.config", "/tmp/operator-jaas.conf");
        ZooKeeperSasl.installClientEntry("starrocks/_HOST@EXAMPLE.COM", "/etc/security/keytabs/fe.keytab");

        Assertions.assertSame(priorConfiguration, Configuration.getConfiguration());
    }
}
