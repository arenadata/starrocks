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

package com.starrocks.credential;

import com.starrocks.common.Config;
import com.starrocks.connector.hadoop.HadoopExt;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.UserIdentity;
import com.starrocks.thrift.TCloudConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class HadoopImpersonationTest {
    private static final String CONF_STRING = "CloudConfiguration{hdpuser='catalog_user'}";

    private final boolean savedEnabled = Config.enable_hadoop_impersonation;

    @BeforeEach
    public void setUpContext() {
        ConnectContext context = new ConnectContext(null);
        context.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp("alice", "%"));
        context.setThreadLocalInfo();
    }

    @AfterEach
    public void tearDown() {
        Config.enable_hadoop_impersonation = savedEnabled;
        ConnectContext.remove();
    }

    private static TCloudConfiguration cloudConfiguration() {
        TCloudConfiguration tCloudConfiguration = new TCloudConfiguration();
        Map<String, String> properties = new HashMap<>();
        properties.put(HadoopExt.HADOOP_USERNAME, "catalog_user");
        properties.put(HadoopExt.HADOOP_CLOUD_CONFIGURATION_STRING, CONF_STRING);
        tCloudConfiguration.setCloud_properties(properties);
        return tCloudConfiguration;
    }

    @Test
    public void testDisabledByDefault() {
        Config.enable_hadoop_impersonation = false;

        TCloudConfiguration tCloudConfiguration = cloudConfiguration();
        HadoopImpersonation.apply(tCloudConfiguration);

        Assertions.assertNull(HadoopImpersonation.currentUser());
        Map<String, String> properties = tCloudConfiguration.getCloud_properties();
        Assertions.assertEquals("catalog_user", properties.get(HadoopExt.HADOOP_USERNAME));
        Assertions.assertEquals(CONF_STRING, properties.get(HadoopExt.HADOOP_CLOUD_CONFIGURATION_STRING));
        Assertions.assertNull(properties.get(HadoopExt.HADOOP_IMPERSONATION_ENABLED));
    }

    @Test
    public void testEnabledReplacesUser() {
        Config.enable_hadoop_impersonation = true;

        TCloudConfiguration tCloudConfiguration = cloudConfiguration();
        HadoopImpersonation.apply(tCloudConfiguration);

        Assertions.assertEquals("alice", HadoopImpersonation.currentUser());
        Map<String, String> properties = tCloudConfiguration.getCloud_properties();
        Assertions.assertEquals("alice", properties.get(HadoopExt.HADOOP_USERNAME));
        Assertions.assertEquals("true", properties.get(HadoopExt.HADOOP_IMPERSONATION_ENABLED));
    }

    /**
     * The BE keys its file system cache on the whole property map, so two users must not end up
     * with the same configuration string.
     */
    @Test
    public void testConfStringDistinguishesUsers() {
        Config.enable_hadoop_impersonation = true;

        TCloudConfiguration forAlice = cloudConfiguration();
        HadoopImpersonation.apply(forAlice);

        ConnectContext context = new ConnectContext(null);
        context.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp("bob", "%"));
        context.setThreadLocalInfo();
        TCloudConfiguration forBob = cloudConfiguration();
        HadoopImpersonation.apply(forBob);

        Assertions.assertNotEquals(
                forAlice.getCloud_properties().get(HadoopExt.HADOOP_CLOUD_CONFIGURATION_STRING),
                forBob.getCloud_properties().get(HadoopExt.HADOOP_CLOUD_CONFIGURATION_STRING));
    }

    @Test
    public void testNoConnectContext() {
        Config.enable_hadoop_impersonation = true;
        ConnectContext.remove();

        Assertions.assertNull(HadoopImpersonation.currentUser());
    }

    /**
     * Background jobs run as the built-in root user, which no Hadoop cluster is willing to be
     * proxied as. They must keep the cluster principal.
     */
    @Test
    public void testStatisticsContextKeepsPrincipal() {
        Config.enable_hadoop_impersonation = true;
        ConnectContext context = new ConnectContext(null);
        context.setCurrentUserIdentity(UserIdentity.ROOT);
        context.setStatisticsContext(true);
        context.setThreadLocalInfo();

        Assertions.assertNull(HadoopImpersonation.currentUser());
    }

    @Test
    public void testMetadataContextKeepsPrincipal() {
        Config.enable_hadoop_impersonation = true;
        ConnectContext context = new ConnectContext(null);
        context.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp("alice", "%"));
        context.setMetadataContext(true);
        context.setThreadLocalInfo();

        Assertions.assertNull(HadoopImpersonation.currentUser());
    }

    @Test
    public void testRootKeepsPrincipal() {
        Config.enable_hadoop_impersonation = true;
        ConnectContext context = new ConnectContext(null);
        context.setCurrentUserIdentity(UserIdentity.ROOT);
        context.setThreadLocalInfo();

        Assertions.assertNull(HadoopImpersonation.currentUser());
    }
}
