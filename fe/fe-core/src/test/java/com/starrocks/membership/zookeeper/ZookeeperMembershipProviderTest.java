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

import com.starrocks.common.Config;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.ComputeNodeSpec;
import com.starrocks.membership.FrontendSpec;
import com.starrocks.membership.HostPort;
import com.starrocks.membership.MemberInfo;
import com.starrocks.membership.MembershipContext;
import com.starrocks.membership.MembershipException;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ZookeeperMembershipProviderTest {

    @TempDir
    Path tempDir;

    private InProcessZooKeeper server;
    private final List<ZookeeperMembershipProvider> providers = new ArrayList<>();

    @BeforeEach
    public void startServer() throws Exception {
        server = new InProcessZooKeeper(tempDir.resolve("zk"));
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (ZookeeperMembershipProvider provider : providers) {
            provider.close();
        }
        providers.clear();
        server.close();
        Config.fe_membership_zookeeper_servers = "";
        Config.fe_membership_zookeeper_root = "/starrocks/fe-membership";
        Config.fe_membership_zookeeper_session_timeout_ms = 30000;
        Config.fe_membership_zookeeper_acl = "none";
    }

    private ZookeeperMembershipProvider started(String host) throws Exception {
        return started(host, null, null);
    }

    private ZookeeperMembershipProvider started(String host, String authScheme, String auth) throws Exception {
        Config.fe_membership_zookeeper_servers = server.connectString();
        ZookeeperMembershipProvider provider = new ZookeeperMembershipProvider();
        if (authScheme != null) {
            provider.setSessionAuthForTest(authScheme, auth);
        }
        provider.start(new MembershipContext(new HostPort(host, 9010), FrontendNodeType.FOLLOWER, false));
        providers.add(provider);
        return provider;
    }

    @Test
    public void testFirstStartedFeIsTheOnlyBootstrapCandidate() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        ZookeeperMembershipProvider second = started("10.0.0.2");

        Assertions.assertTrue(first.isBootstrapCandidate());
        Assertions.assertFalse(second.isBootstrapCandidate());
        Assertions.assertTrue(second.seeds().isEmpty());
        Assertions.assertEquals(Optional.empty(), second.existingClusterId());
    }

    @Test
    public void testRecordedClusterIdIsVisibleToEveryFeAndRefusesToChange() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        first.recordClusterId("4242");

        ZookeeperMembershipProvider second = started("10.0.0.2");
        Assertions.assertEquals(Optional.of("4242"), second.existingClusterId());
        first.recordClusterId("4242");
        Assertions.assertThrows(MembershipException.class, () -> second.recordClusterId("9999"));
        Assertions.assertEquals(Optional.of("4242"), first.existingClusterId());
    }

    @Test
    public void testPublishedTokenIsReadableByEveryFe() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        Assertions.assertNull(first.readToken());

        first.publishToken("cluster-secret");

        ZookeeperMembershipProvider second = started("10.0.0.2");
        Assertions.assertEquals("cluster-secret", second.readToken());
    }

    @Test
    public void testPublishTokenKeepsTheRecordedValue() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        first.publishToken("cluster-secret");

        // a wiped FE rejoining carries a fresh token and must not overwrite the record
        first.publishToken("another-token");

        Assertions.assertEquals("cluster-secret", first.readToken());
    }

    @Test
    public void testDeletedTokenRecordSelfHeals() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        first.publishToken("cluster-secret");
        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.delete().forPath(Config.fe_membership_zookeeper_root + "/token");
            Assertions.assertNull(first.readToken());
        }

        first.seeds();

        Assertions.assertEquals("cluster-secret", first.readToken());
    }

    @Test
    public void testAnnouncedFrontendIsSeedAndDesired() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        ZookeeperMembershipProvider second = started("10.0.0.2");
        Assertions.assertEquals(Optional.empty(), second.expectedFrontends());

        first.announce(new MemberInfo(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, "fe1", "4242"));

        Assertions.assertEquals(List.of(new HostPort("10.0.0.1", 9010)), second.seeds());
        Assertions.assertEquals(Optional.of(Set.of(new FrontendSpec(new HostPort("10.0.0.1", 9010),
                FrontendNodeType.FOLLOWER))), second.expectedFrontends());
    }

    @Test
    public void testDesiredComputeNodesComeFromOperatorNodes() throws Exception {
        ZookeeperMembershipProvider leader = started("10.0.0.1");
        Assertions.assertEquals(Optional.empty(), leader.expectedComputeNodes());

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            String nodes = Config.fe_membership_zookeeper_root + "/compute_nodes";
            ops.create().creatingParentsIfNeeded().forPath(nodes + "/10.0.0.1:9050",
                    "{\"warehouse\":\"wh1\",\"cnGroup\":\"g1\"}".getBytes(StandardCharsets.UTF_8));
            ops.create().forPath(nodes + "/10.0.0.2:9050", new byte[0]);
            ops.create().forPath(nodes + "/not-a-node", "{\"bogus\":true}".getBytes(StandardCharsets.UTF_8));
        }

        Assertions.assertEquals(Optional.of(Set.of(
                        new ComputeNodeSpec(new HostPort("10.0.0.1", 9050), "wh1", "g1"),
                        new ComputeNodeSpec(new HostPort("10.0.0.2", 9050)))),
                leader.expectedComputeNodes());
    }

    @Test
    public void testStartRejectsMissingServers() {
        Config.fe_membership_zookeeper_servers = "";
        ZookeeperMembershipProvider provider = new ZookeeperMembershipProvider();
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> provider.start(
                new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false)));
        Assertions.assertTrue(e.getMessage().contains("fe_membership_zookeeper_servers"));
    }

    @Test
    public void testExpiredSessionDropsAnnouncementAndReconnectRegistersAgain() throws Exception {
        Config.fe_membership_zookeeper_session_timeout_ms = 5000;
        ZookeeperMembershipProvider first = started("10.0.0.1");
        ZookeeperMembershipProvider second = started("10.0.0.2");
        long session = first.sessionId();
        first.announce(new MemberInfo(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, "fe1", "4242"));
        Assertions.assertEquals(List.of(new HostPort("10.0.0.1", 9010)), second.seeds());

        server.closeSession(session);

        HostPort announcedFe = new HostPort("10.0.0.1", 9010);
        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(
                () -> Assertions.assertEquals(List.of(announcedFe), second.seeds()));
        Assertions.assertTrue(first.isBootstrapCandidate() ^ second.isBootstrapCandidate());
    }

    @Test
    public void testDeletedRegistrationSelfHealsOnNextCall() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        ZookeeperMembershipProvider second = started("10.0.0.2");
        first.announce(new MemberInfo(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, "fe1", "4242"));

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.delete().forPath(Config.fe_membership_zookeeper_root + "/frontends/10.0.0.1:9010");
            for (String child : ops.getChildren().forPath(Config.fe_membership_zookeeper_root + "/starting")) {
                ops.delete().forPath(Config.fe_membership_zookeeper_root + "/starting/" + child);
            }
            Assertions.assertTrue(second.seeds().isEmpty());
        }

        first.isBootstrapCandidate();

        Assertions.assertEquals(List.of(new HostPort("10.0.0.1", 9010)), second.seeds());
    }

    @Test
    public void testMembershipChangeFiresListener() throws Exception {
        ZookeeperMembershipProvider leader = started("10.0.0.1");
        AtomicInteger fired = new AtomicInteger();
        leader.addChangeListener(fired::incrementAndGet);

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.create().creatingParentsIfNeeded().forPath(
                    Config.fe_membership_zookeeper_root + "/compute_nodes/10.0.0.1:9050", new byte[0]);
        }

        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> fired.get() >= 1);
    }

    @Test
    public void testSeedsSortAnnouncedFrontendsAndSkipInvalidChildren() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        ZookeeperMembershipProvider second = started("10.0.0.2");
        first.announce(new MemberInfo(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, "fe1", "4242"));
        second.announce(new MemberInfo(new HostPort("10.0.0.2", 9010), FrontendNodeType.OBSERVER, "fe2", "4242"));
        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.create().forPath(Config.fe_membership_zookeeper_root + "/frontends/garbage-child", new byte[0]);
        }

        Assertions.assertEquals(List.of(new HostPort("10.0.0.1", 9010), new HostPort("10.0.0.2", 9010)),
                second.seeds());
    }

    @Test
    public void testRegistrationSelfHealsWithoutProviderCalls() throws Exception {
        Config.fe_membership_zookeeper_servers = server.connectString();
        ZookeeperMembershipProvider first = new ZookeeperMembershipProvider();
        first.setEnsureIntervalForTest(500);
        first.start(new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false));
        providers.add(first);
        ZookeeperMembershipProvider second = started("10.0.0.2");
        first.announce(new MemberInfo(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, "fe1", "4242"));

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.delete().forPath(Config.fe_membership_zookeeper_root + "/frontends/10.0.0.1:9010");
        }

        HostPort announcedFe = new HostPort("10.0.0.1", 9010);
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(
                () -> Assertions.assertEquals(List.of(announcedFe), second.seeds()));
    }

    @Test
    public void testRootTrailingSlashIsTrimmed() throws Exception {
        Config.fe_membership_zookeeper_root = "/starrocks/fe-membership/";
        ZookeeperMembershipProvider provider = started("10.0.0.1");

        Assertions.assertTrue(provider.isBootstrapCandidate());
        provider.recordClusterId("7");
        Assertions.assertEquals(Optional.of("7"), provider.existingClusterId());
    }

    @Test
    public void testStartingChurnDoesNotFireTheChangeListener() throws Exception {
        ZookeeperMembershipProvider leader = started("10.0.0.1");
        AtomicInteger fired = new AtomicInteger();
        leader.addChangeListener(fired::incrementAndGet);

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            String churn = Config.fe_membership_zookeeper_root + "/starting/churn-0000000099";
            ops.create().forPath(churn, new byte[0]);
            ops.delete().forPath(churn);
        }

        Thread.sleep(2000);
        Assertions.assertEquals(0, fired.get());

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.create().creatingParentsIfNeeded().forPath(
                    Config.fe_membership_zookeeper_root + "/compute_nodes/10.0.0.1:9050", new byte[0]);
        }
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> fired.get() > 0);
    }

    @Test
    public void testCreatorAclProtectsTheMembershipNodes() throws Exception {
        protectNamespace("/starrocks4");
        Config.fe_membership_zookeeper_root = "/starrocks4/fe-membership";
        Config.fe_membership_zookeeper_acl = "sasl";
        ZookeeperMembershipProvider first = started("10.0.0.1", "digest", "fe:secret");
        ZookeeperMembershipProvider second = started("10.0.0.2", "digest", "fe:secret");
        first.announce(new MemberInfo(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, "fe1", "9"));
        first.recordClusterId("9");

        Assertions.assertEquals(List.of(new HostPort("10.0.0.1", 9010)), second.seeds());
        Assertions.assertEquals(Optional.of("9"), second.existingClusterId());

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            String root = Config.fe_membership_zookeeper_root;
            Assertions.assertThrows(KeeperException.NoAuthException.class,
                    () -> ops.getChildren().forPath(root));
            Assertions.assertThrows(KeeperException.NoAuthException.class,
                    () -> ops.setData().forPath(root + "/cluster_id", "evil".getBytes(StandardCharsets.UTF_8)));
            Assertions.assertThrows(KeeperException.NoAuthException.class,
                    () -> ops.delete().forPath(root + "/frontends/10.0.0.1:9010"));
            Assertions.assertThrows(KeeperException.NoAuthException.class,
                    () -> ops.create().forPath(root + "/frontends/evil:9010"));
        }
    }

    @Test
    public void testAclValueIsValidated() throws Exception {
        Config.fe_membership_zookeeper_servers = server.connectString();
        Config.fe_membership_zookeeper_acl = "md5";
        ZookeeperMembershipProvider provider = new ZookeeperMembershipProvider();
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> provider.start(
                new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false)));
        Assertions.assertTrue(e.getMessage().contains("fe_membership_zookeeper_acl"));
    }

    @Test
    public void testWorldWritableAncestorFailsFast() throws Exception {
        Config.fe_membership_zookeeper_acl = "sasl";
        Config.fe_membership_zookeeper_servers = server.connectString();
        ZookeeperMembershipProvider provider = new ZookeeperMembershipProvider();
        provider.setSessionAuthForTest("digest", "fe:secret");
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> provider.start(
                new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false)));
        Assertions.assertTrue(e.getMessage().contains("ancestor"), e.getMessage());

        protectNamespace("/protected");
        Config.fe_membership_zookeeper_root = "/protected/fe-membership";
        ZookeeperMembershipProvider second = new ZookeeperMembershipProvider();
        second.setSessionAuthForTest("digest", "fe:secret");
        second.start(new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false));
        providers.add(second);
        Assertions.assertTrue(second.isBootstrapCandidate());
    }

    @Test
    public void testUnauthenticatedSessionGetsTheAclHint() throws Exception {
        protectNamespace("/protected2");
        Config.fe_membership_zookeeper_root = "/protected2/fe-membership";
        Config.fe_membership_zookeeper_acl = "sasl";
        Config.fe_membership_zookeeper_servers = server.connectString();

        ZookeeperMembershipProvider provider = new ZookeeperMembershipProvider();
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> provider.start(
                new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false)));
        Assertions.assertTrue(e.getMessage().contains("authenticated"), e.getMessage());
    }

    @Test
    public void testSecondPrincipalGetsTheAclHint() throws Exception {
        protectNamespace("/starrocks2");
        Config.fe_membership_zookeeper_root = "/starrocks2/fe-membership";
        Config.fe_membership_zookeeper_acl = "sasl";
        Config.fe_membership_zookeeper_servers = server.connectString();

        ZookeeperMembershipProvider first = new ZookeeperMembershipProvider();
        first.setSessionAuthForTest("digest", "fe:secret");
        first.start(new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false));
        providers.add(first);

        ZookeeperMembershipProvider second = new ZookeeperMembershipProvider();
        second.setSessionAuthForTest("digest", "someone-else:other");
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> second.start(
                new MembershipContext(new HostPort("10.0.0.2", 9010), FrontendNodeType.FOLLOWER, false)));
        Assertions.assertTrue(e.getMessage().contains("principal"), e.getMessage());
    }

    @Test
    public void testPreexistingOpenTreeIsRejectedUnderSasl() throws Exception {
        protectNamespace("/starrocks3");
        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.getZookeeperClient().getZooKeeper()
                    .addAuthInfo("digest", "fe:secret".getBytes(StandardCharsets.UTF_8));
            ops.create().forPath("/starrocks3/fe-membership");
        }
        Config.fe_membership_zookeeper_root = "/starrocks3/fe-membership";
        Config.fe_membership_zookeeper_acl = "sasl";
        Config.fe_membership_zookeeper_servers = server.connectString();
        ZookeeperMembershipProvider provider = new ZookeeperMembershipProvider();
        provider.setSessionAuthForTest("digest", "fe:secret");
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> provider.start(
                new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false)));
        Assertions.assertTrue(e.getMessage().contains("/starrocks3/fe-membership"), e.getMessage());
    }

    @Test
    public void testDeletedTreeSelfHealsOnNextCall() throws Exception {
        ZookeeperMembershipProvider first = started("10.0.0.1");
        ZookeeperMembershipProvider second = started("10.0.0.2");

        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.delete().deletingChildrenIfNeeded().forPath(Config.fe_membership_zookeeper_root);
        }

        Assertions.assertTrue(first.isBootstrapCandidate());
        Assertions.assertTrue(second.seeds().isEmpty());
    }

    @Test
    public void testCreatorAclVouchesForRegisteredFrontendsOnly() throws Exception {
        Config.fe_membership_zookeeper_acl = "none";
        ZookeeperMembershipProvider plain = started("10.0.0.1");
        Assertions.assertTrue(plain.requiresToken());
        Assertions.assertFalse(plain.vouches(new HostPort("10.0.0.1", 9010)));

        protectNamespace("/starrocks5");
        Config.fe_membership_zookeeper_root = "/starrocks5/fe-membership";
        Config.fe_membership_zookeeper_acl = "sasl";
        Config.fe_membership_zookeeper_servers = server.connectString();
        ZookeeperMembershipProvider first = new ZookeeperMembershipProvider();
        first.setSessionAuthForTest("digest", "fe:secret");
        first.start(new MembershipContext(new HostPort("10.0.0.1", 9010), FrontendNodeType.FOLLOWER, false));
        providers.add(first);

        Assertions.assertFalse(first.requiresToken());
        Assertions.assertTrue(first.vouches(new HostPort("10.0.0.1", 9010)));
        Assertions.assertFalse(first.vouches(new HostPort("10.9.9.9", 9010)));
        Assertions.assertFalse(first.vouches(new HostPort("10.0.0.1", 9050)));
    }

    /** Locks "/" and creates the parent with the creator ACL, as an ensemble admin would. */
    private void protectNamespace(String parent) throws Exception {
        try (CuratorFramework ops = CuratorFrameworkFactory.newClient(server.connectString(),
                new RetryOneTime(1000))) {
            ops.start();
            ops.getZookeeperClient().getZooKeeper()
                    .addAuthInfo("digest", "fe:secret".getBytes(StandardCharsets.UTF_8));
            ops.setACL().withACL(ZooDefs.Ids.CREATOR_ALL_ACL).forPath("/");
            ops.create().withACL(ZooDefs.Ids.CREATOR_ALL_ACL).forPath(parent);
        }
    }
}
