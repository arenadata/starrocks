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
import org.apache.zookeeper.server.NIOServerCnxnFactory;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ZookeeperMembershipProviderTest {

    /** Standalone in-process ZooKeeper: its handle allows expiring a client session on purpose. */
    private static final class EmbeddedZooKeeper implements Closeable {
        private final ZooKeeperServer server;
        private final NIOServerCnxnFactory factory;

        EmbeddedZooKeeper(Path dataDir) throws IOException, InterruptedException {
            Files.createDirectories(dataDir);
            server = new ZooKeeperServer(dataDir.toFile(), dataDir.toFile(), 2000);
            factory = new NIOServerCnxnFactory();
            factory.configure(new InetSocketAddress("127.0.0.1", 0), 0);
            factory.startup(server);
        }

        String connectString() {
            return "127.0.0.1:" + factory.getLocalPort();
        }

        void closeSession(long sessionId) {
            server.closeSession(sessionId);
        }

        @Override
        public void close() throws IOException {
            factory.shutdown();
            server.getZKDatabase().close();
        }
    }

    @TempDir
    Path tempDir;

    private EmbeddedZooKeeper server;
    private final List<ZookeeperMembershipProvider> providers = new ArrayList<>();

    @BeforeEach
    public void startServer() throws Exception {
        server = new EmbeddedZooKeeper(tempDir.resolve("zk"));
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
    }

    private ZookeeperMembershipProvider started(String host) throws Exception {
        Config.fe_membership_zookeeper_servers = server.connectString();
        ZookeeperMembershipProvider provider = new ZookeeperMembershipProvider();
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
}
