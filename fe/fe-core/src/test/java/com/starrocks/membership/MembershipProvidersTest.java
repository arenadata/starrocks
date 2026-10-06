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

package com.starrocks.membership;

import com.starrocks.common.Config;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.embedded.EmbeddedMembershipProvider;
import com.starrocks.membership.zookeeper.InProcessZooKeeper;
import com.starrocks.service.FrontendOptions;
import mockit.Mock;
import mockit.MockUp;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.zookeeper.ZooDefs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class MembershipProvidersTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    public void mockLocalAddress() {
        new MockUp<FrontendOptions>() {
            @Mock
            public String getLocalHostAddress() {
                return "127.0.0.1";
            }

            @Mock
            public boolean isUseFqdn() {
                return false;
            }
        };
    }

    @AfterEach
    public void reset() {
        MembershipProviders.close();
        Config.fe_membership_provider = "none";
        Config.fe_membership_role = "FOLLOWER";
        Config.fe_cluster_initial_state = "existing";
        Config.fe_seed_nodes = "";
        Config.auth_token = "";
    }

    @Test
    public void testNoneLeavesProviderUnset() throws MembershipException {
        Config.fe_membership_provider = "none";
        MembershipProviders.init(null);
        Assertions.assertFalse(MembershipProviders.isEnabled());
        Assertions.assertTrue(MembershipProviders.current().isEmpty());

        Config.fe_membership_provider = "";
        MembershipProviders.init(null);
        Assertions.assertFalse(MembershipProviders.isEnabled());
    }

    @Test
    public void testProviderRequiresAuthToken() {
        Config.fe_membership_provider = "embedded";
        Config.auth_token = "";
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> MembershipProviders.init(null));
        Assertions.assertTrue(e.getMessage().contains("requires auth_token"), e.getMessage());
    }

    @Test
    public void testVouchingProviderSkipsTheTokenRequirement() throws Exception {
        try (InProcessZooKeeper zooKeeper = new InProcessZooKeeper(tempDir.resolve("zk"))) {
            try (CuratorFramework ops = CuratorFrameworkFactory.newClient(zooKeeper.connectString(),
                    new RetryOneTime(1000))) {
                ops.start();
                ops.getZookeeperClient().getZooKeeper()
                        .addAuthInfo("digest", "fe:secret".getBytes(StandardCharsets.UTF_8));
                ops.setACL().withACL(ZooDefs.Ids.CREATOR_ALL_ACL).forPath("/");
                ops.create().withACL(ZooDefs.Ids.CREATOR_ALL_ACL).forPath("/vouching");
            }

            Config.fe_membership_provider = "zookeeper";
            Config.auth_token = "";
            Config.fe_membership_zookeeper_servers = zooKeeper.connectString();
            Config.fe_membership_zookeeper_root = "/vouching/fe-membership";
            Config.fe_membership_zookeeper_acl = "sasl";

            // no kerberos in the UT, so the session cannot authenticate and start() fails on the
            // creator ACL — but not on the auth_token requirement the test is about
            MembershipException e = Assertions.assertThrows(MembershipException.class,
                    () -> MembershipProviders.init(null));
            Assertions.assertFalse(e.getMessage().contains("requires auth_token"), e.getMessage());
            Assertions.assertTrue(e.getMessage().contains("authenticated"), e.getMessage());
        } finally {
            MembershipProviders.close();
            Config.fe_membership_zookeeper_servers = "";
            Config.fe_membership_zookeeper_root = "/starrocks/fe-membership";
            Config.fe_membership_zookeeper_acl = "none";
        }
    }

    @Test
    public void testInvalidRoleAndState() {
        Config.fe_membership_provider = "embedded";
        Config.auth_token = "secret";

        Config.fe_membership_role = "LEADER";
        Assertions.assertThrows(MembershipException.class, () -> MembershipProviders.init(null));

        Config.fe_membership_role = "observer";
        Config.fe_cluster_initial_state = "maybe";
        Assertions.assertThrows(MembershipException.class, () -> MembershipProviders.init(null));
    }

    @Test
    public void testUnknownProvider() {
        Config.fe_membership_provider = "consul";
        Config.auth_token = "secret";
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> MembershipProviders.init(null));
        Assertions.assertTrue(e.getMessage().contains("unknown fe_membership_provider"), e.getMessage());
    }

    @Test
    public void testEmbeddedProviderStarts() throws MembershipException {
        Config.fe_membership_provider = " Embedded ";
        Config.fe_membership_role = "observer";
        Config.fe_cluster_initial_state = "NEW";
        Config.fe_seed_nodes = "127.0.0.1:9010";
        Config.auth_token = "secret";

        MembershipProviders.init(null);
        Assertions.assertTrue(MembershipProviders.isEnabled());
        Assertions.assertTrue(MembershipProviders.current().orElseThrow() instanceof EmbeddedMembershipProvider);
        Assertions.assertTrue(MembershipProviders.isInitialStateNew());

        MembershipContext ctx = MembershipProviders.context().orElseThrow();
        Assertions.assertEquals(new HostPort("127.0.0.1", Config.edit_log_port), ctx.self());
        Assertions.assertEquals(FrontendNodeType.OBSERVER, ctx.desiredRole());
        Assertions.assertFalse(ctx.useFqdn());
        Assertions.assertTrue(MembershipProviders.current().orElseThrow().isBootstrapCandidate());

        MembershipProviders.close();
        Assertions.assertFalse(MembershipProviders.isEnabled());
    }
}
