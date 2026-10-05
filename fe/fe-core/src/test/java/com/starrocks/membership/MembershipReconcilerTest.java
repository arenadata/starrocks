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
import com.starrocks.ha.HAProtocol;
import com.starrocks.leader.CheckpointController;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.Frontend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public class MembershipReconcilerTest {
    private static final HostPort SELF = new HostPort("192.168.8.1", 9010);
    private static final long GRACE_MS = 600_000L;

    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    private HAProtocol previousHaProtocol;
    private CheckpointController previousController;

    private static class LeaderNodeMgr extends NodeMgr {
        LeaderNodeMgr() {
            super(FrontendNodeType.LEADER, "leader", SELF.toPair());
        }
    }

    private static class NoopHAProtocol implements HAProtocol {
        @Override
        public boolean fencing() {
            return true;
        }

        @Override
        public InetSocketAddress getLeader() {
            return null;
        }

        @Override
        public String getLeaderNodeName() {
            return null;
        }

        @Override
        public List<InetSocketAddress> getObserverNodes() {
            return List.of();
        }

        @Override
        public List<InetSocketAddress> getElectableNodes(boolean leaderIncluded) {
            return List.of();
        }

        @Override
        public boolean removeElectableNode(String nodeName) {
            return true;
        }

        @Override
        public long getLatestEpoch() {
            return 0;
        }

        @Override
        public void removeUnstableNode(String nodeName, int currentFollowerCnt) {
        }
    }

    @BeforeAll
    public static void setUpClass() {
        UtFrameUtils.setUpForPersistTest();
        GlobalStateMgr.getCurrentState().initDefaultWarehouse();
    }

    @BeforeEach
    public void setUp() {
        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        previousHaProtocol = globalStateMgr.getHaProtocol();
        previousController = globalStateMgr.getCheckpointController();
        globalStateMgr.setHaProtocol(new NoopHAProtocol());
        // dropFrontendHook() cancels the checkpoint of the dropped node
        globalStateMgr.setCheckpointController(
                new CheckpointController("membership_test_checkpoint_controller", globalStateMgr.getJournal(), ""));
        Config.fe_membership_auto_drop_fe = true;
        Config.fe_membership_auto_add_cn = true;
        Config.fe_membership_auto_drop_cn = true;
        Config.fe_membership_drop_grace_seconds = (int) (GRACE_MS / 1000);
    }

    @AfterEach
    public void tearDown() {
        GlobalStateMgr globalStateMgr = GlobalStateMgr.getCurrentState();
        globalStateMgr.setHaProtocol(previousHaProtocol);
        globalStateMgr.setCheckpointController(previousController);
        Config.fe_membership_auto_drop_fe = false;
        Config.fe_membership_auto_add_cn = true;
        Config.fe_membership_auto_drop_cn = false;
        Config.fe_membership_drop_grace_seconds = 600;
    }

    private MembershipReconciler reconciler(FakeMembershipProvider provider, NodeMgr nodeMgr,
                                            SystemInfoService systemInfo) {
        return new MembershipReconciler(provider, nodeMgr, systemInfo, now::get);
    }

    private static NodeMgr clusterOfThree() {
        NodeMgr nodeMgr = new LeaderNodeMgr();
        nodeMgr.replayAddFrontend(new Frontend(FrontendNodeType.FOLLOWER, "leader", SELF.host(), SELF.port()));
        nodeMgr.replayAddFrontend(new Frontend(FrontendNodeType.FOLLOWER, "fe2", "192.168.8.2", 9010));
        nodeMgr.replayAddFrontend(new Frontend(FrontendNodeType.OBSERVER, "fe3", "192.168.8.3", 9010));
        return nodeMgr;
    }

    private static Frontend fe(NodeMgr nodeMgr, String host) {
        return nodeMgr.checkFeExist(host, 9010);
    }

    @Test
    public void testDropsUndesiredDeadFrontendAfterGrace() throws Exception {
        NodeMgr nodeMgr = clusterOfThree();
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.expectedFrontends = Optional.of(Set.of(
                new FrontendSpec(new HostPort("192.168.8.2", 9010), FrontendNodeType.FOLLOWER)));
        MembershipReconciler reconciler = reconciler(provider, nodeMgr, new SystemInfoService());

        // desired but dead, and undesired but alive: both stay
        fe(nodeMgr, "192.168.8.2").setAlive(false);
        fe(nodeMgr, "192.168.8.3").setAlive(true);
        reconciler.reconcileFrontends();
        Assertions.assertEquals(3, nodeMgr.getAllFrontends().size());

        // undesired and dead: dropped once the grace period has passed
        fe(nodeMgr, "192.168.8.3").setAlive(false);
        reconciler.reconcileFrontends();
        Assertions.assertEquals(3, nodeMgr.getAllFrontends().size());
        now.addAndGet(GRACE_MS - 1000);
        reconciler.reconcileFrontends();
        Assertions.assertEquals(3, nodeMgr.getAllFrontends().size());
        now.addAndGet(2000);
        reconciler.reconcileFrontends();
        Assertions.assertNull(fe(nodeMgr, "192.168.8.3"));
        Assertions.assertNotNull(fe(nodeMgr, "192.168.8.2"));
        Assertions.assertNotNull(fe(nodeMgr, SELF.host()));
    }

    @Test
    public void testNothingDroppedWithoutDesiredSetOrFlag() throws Exception {
        NodeMgr nodeMgr = clusterOfThree();
        fe(nodeMgr, "192.168.8.2").setAlive(false);
        fe(nodeMgr, "192.168.8.3").setAlive(false);
        FakeMembershipProvider provider = new FakeMembershipProvider();
        MembershipReconciler reconciler = reconciler(provider, nodeMgr, new SystemInfoService());

        provider.expectedFrontends = Optional.empty();
        now.addAndGet(2 * GRACE_MS);
        reconciler.reconcileFrontends();
        Assertions.assertEquals(3, nodeMgr.getAllFrontends().size());

        provider.expectedFrontends = Optional.of(Set.of());
        Config.fe_membership_auto_drop_fe = false;
        now.addAndGet(2 * GRACE_MS);
        reconciler.reconcileFrontends();
        Assertions.assertEquals(3, nodeMgr.getAllFrontends().size());
    }

    @Test
    public void testSelfIsNeverDroppedAndOneDropPerRound() throws Exception {
        NodeMgr nodeMgr = clusterOfThree();
        fe(nodeMgr, SELF.host()).setAlive(false);
        fe(nodeMgr, "192.168.8.2").setAlive(false);
        fe(nodeMgr, "192.168.8.3").setAlive(false);
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.expectedFrontends = Optional.of(Set.of());
        MembershipReconciler reconciler = reconciler(provider, nodeMgr, new SystemInfoService());

        reconciler.reconcileFrontends();
        now.addAndGet(GRACE_MS + 1000);
        reconciler.reconcileFrontends();
        Assertions.assertEquals(2, nodeMgr.getAllFrontends().size());
        reconciler.reconcileFrontends();
        Assertions.assertEquals(1, nodeMgr.getAllFrontends().size());
        Assertions.assertNotNull(fe(nodeMgr, SELF.host()));
        reconciler.reconcileFrontends();
        Assertions.assertNotNull(fe(nodeMgr, SELF.host()));
    }

    @Test
    public void testAddsAndDropsComputeNodes() throws Exception {
        SystemInfoService systemInfo = new SystemInfoService();
        FakeMembershipProvider provider = new FakeMembershipProvider();
        HostPort cnAddress = new HostPort("192.168.8.11", 9050);
        provider.expectedComputeNodes = Optional.of(Set.of(new ComputeNodeSpec(cnAddress)));
        MembershipReconciler reconciler = reconciler(provider, new LeaderNodeMgr(), systemInfo);

        reconciler.reconcileComputeNodes();
        ComputeNode cn = systemInfo.getComputeNodeWithHeartbeatPort(cnAddress.host(), cnAddress.port());
        Assertions.assertNotNull(cn);
        reconciler.reconcileComputeNodes();
        Assertions.assertEquals(1, systemInfo.getComputeNodes().size());

        // undesired but alive: kept
        provider.expectedComputeNodes = Optional.of(Set.of());
        cn.setAlive(true);
        now.addAndGet(2 * GRACE_MS);
        reconciler.reconcileComputeNodes();
        Assertions.assertEquals(1, systemInfo.getComputeNodes().size());

        // undesired and dead: dropped after the grace period
        cn.setAlive(false);
        reconciler.reconcileComputeNodes();
        Assertions.assertEquals(1, systemInfo.getComputeNodes().size());
        now.addAndGet(GRACE_MS + 1000);
        reconciler.reconcileComputeNodes();
        Assertions.assertTrue(systemInfo.getComputeNodes().isEmpty());

        // auto add switched off: the desired node is not registered again
        provider.expectedComputeNodes = Optional.of(Set.of(new ComputeNodeSpec(cnAddress)));
        Config.fe_membership_auto_add_cn = false;
        reconciler.reconcileComputeNodes();
        Assertions.assertTrue(systemInfo.getComputeNodes().isEmpty());
    }
}
