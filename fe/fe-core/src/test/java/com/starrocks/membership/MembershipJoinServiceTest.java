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

import com.starrocks.common.Pair;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.MembershipJoinService.ComputeNodeJoinResult;
import com.starrocks.membership.MembershipJoinService.FrontendJoinResult;
import com.starrocks.membership.MembershipJoinService.JoinException;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.system.SystemInfoService;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class MembershipJoinServiceTest {

    @BeforeAll
    public static void setUp() {
        UtFrameUtils.setUpForPersistTest();
        GlobalStateMgr.getCurrentState().initDefaultWarehouse();
    }

    /** Exposes the protected test constructor of NodeMgr. */
    private static class LeaderNodeMgr extends NodeMgr {
        LeaderNodeMgr() {
            super(FrontendNodeType.LEADER, "leader", Pair.create("192.168.7.1", 9010));
        }
    }

    private static NodeMgr leaderNodeMgr() {
        return new LeaderNodeMgr();
    }

    @Test
    public void testJoinFrontendIsIdempotent() throws Exception {
        NodeMgr nodeMgr = leaderNodeMgr();
        MembershipJoinService service = new MembershipJoinService(nodeMgr, new SystemInfoService());
        HostPort node = new HostPort("192.168.7.2", 9010);

        FrontendJoinResult first = service.joinFrontend(node, FrontendNodeType.FOLLOWER);
        Assertions.assertFalse(first.existed());
        Assertions.assertEquals(FrontendNodeType.FOLLOWER, first.role());
        Assertions.assertTrue(first.nodeName().startsWith("192.168.7.2_9010_"), first.nodeName());
        Assertions.assertEquals(new HostPort("192.168.7.1", 9010), first.helper());
        Assertions.assertEquals(String.valueOf(nodeMgr.getClusterId()), first.clusterId());

        // a registered node keeps its role and name whatever it asks for
        FrontendJoinResult again = service.joinFrontend(node, FrontendNodeType.OBSERVER);
        Assertions.assertTrue(again.existed());
        Assertions.assertEquals(first.nodeName(), again.nodeName());
        Assertions.assertEquals(FrontendNodeType.FOLLOWER, again.role());
        Assertions.assertEquals(1, nodeMgr.getFrontends(FrontendNodeType.FOLLOWER).size());

        // one FE per host
        JoinException conflict = Assertions.assertThrows(JoinException.class,
                () -> service.joinFrontend(new HostPort("192.168.7.2", 9011), FrontendNodeType.FOLLOWER));
        Assertions.assertEquals(MembershipJoinService.STATUS_CONFLICT, conflict.getStatus());

        FrontendJoinResult observer = service.joinFrontend(new HostPort("192.168.7.3", 9010), FrontendNodeType.OBSERVER);
        Assertions.assertFalse(observer.existed());
        Assertions.assertEquals(FrontendNodeType.OBSERVER, observer.role());
        Assertions.assertEquals(1, nodeMgr.getFrontends(FrontendNodeType.OBSERVER).size());

        JoinException badRole = Assertions.assertThrows(JoinException.class,
                () -> service.joinFrontend(new HostPort("192.168.7.4", 9010), FrontendNodeType.LEADER));
        Assertions.assertEquals(MembershipJoinService.STATUS_BAD_REQUEST, badRole.getStatus());
    }

    @Test
    public void testJoinComputeNodeIsIdempotent() throws Exception {
        SystemInfoService systemInfo = new SystemInfoService();
        MembershipJoinService service = new MembershipJoinService(leaderNodeMgr(), systemInfo);
        HostPort node = new HostPort("192.168.7.9", 9050);

        ComputeNodeJoinResult first = service.joinComputeNode(node, null, null);
        Assertions.assertFalse(first.existed());
        Assertions.assertEquals(WarehouseManager.DEFAULT_WAREHOUSE_NAME, first.warehouse());
        Assertions.assertNotNull(systemInfo.getComputeNodeWithHeartbeatPort("192.168.7.9", 9050));

        ComputeNodeJoinResult again = service.joinComputeNode(node, WarehouseManager.DEFAULT_WAREHOUSE_NAME, "");
        Assertions.assertTrue(again.existed());
        Assertions.assertEquals(first.id(), again.id());
        Assertions.assertEquals(1, systemInfo.getComputeNodes().size());

        JoinException unknownWarehouse = Assertions.assertThrows(JoinException.class,
                () -> service.joinComputeNode(new HostPort("192.168.7.10", 9050), "no_such_warehouse", null));
        Assertions.assertEquals(MembershipJoinService.STATUS_CONFLICT, unknownWarehouse.getStatus());
        Assertions.assertEquals(1, systemInfo.getComputeNodes().size());
    }
}
