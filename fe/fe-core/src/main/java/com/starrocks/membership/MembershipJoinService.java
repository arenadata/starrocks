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

import com.google.common.base.Strings;
import com.starrocks.common.DdlException;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.Frontend;
import com.starrocks.system.SystemInfoService;
import com.starrocks.warehouse.Warehouse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Leader-side registration of a node that asks to join: the same effect as ALTER SYSTEM ADD, idempotent
 * for a node that is already registered.
 */
public final class MembershipJoinService {
    private static final Logger LOG = LogManager.getLogger(MembershipJoinService.class);

    public static final int STATUS_BAD_REQUEST = 400;
    public static final int STATUS_CONFLICT = 409;
    public static final int STATUS_UNAVAILABLE = 503;

    /** Registration failure together with the HTTP status that describes it. */
    public static final class JoinException extends Exception {
        private final int status;

        public JoinException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /**
     * @param helper  leader address the joiner must use as --helper
     * @param existed true when the node was registered before this call
     */
    public record FrontendJoinResult(FrontendNodeType role, String nodeName, HostPort helper, String clusterId,
                                     boolean existed) {
    }

    public record ComputeNodeJoinResult(long id, String warehouse, boolean existed) {
    }

    private final NodeMgr nodeMgr;
    private final SystemInfoService systemInfo;

    public MembershipJoinService(NodeMgr nodeMgr, SystemInfoService systemInfo) {
        this.nodeMgr = nodeMgr;
        this.systemInfo = systemInfo;
    }

    public static MembershipJoinService forCurrentState() {
        NodeMgr nodeMgr = GlobalStateMgr.getCurrentState().getNodeMgr();
        return new MembershipJoinService(nodeMgr, nodeMgr.getClusterInfo());
    }

    /**
     * Registers an FE. A node already registered with the same host and edit log port keeps its current
     * role and name; the joiner takes them from the /role handshake anyway.
     */
    public FrontendJoinResult joinFrontend(HostPort node, FrontendNodeType role) throws JoinException {
        if (role != FrontendNodeType.FOLLOWER && role != FrontendNodeType.OBSERVER) {
            throw new JoinException(STATUS_BAD_REQUEST, "role must be FOLLOWER or OBSERVER, got " + role);
        }
        Frontend fe = nodeMgr.checkFeExist(node.host(), node.port());
        boolean existed = fe != null;
        if (fe == null) {
            try {
                nodeMgr.addFrontend(role, node.host(), node.port());
            } catch (DdlException e) {
                throw new JoinException(isRetryable(e) ? STATUS_UNAVAILABLE : STATUS_CONFLICT, e.getMessage());
            }
            fe = nodeMgr.checkFeExist(node.host(), node.port());
            if (fe == null) {
                throw new JoinException(STATUS_UNAVAILABLE, "frontend " + node + " was not registered, retry");
            }
            LOG.info("frontend {} joined as {} with name {}", node, fe.getRole(), fe.getNodeName());
        } else if (fe.getRole() != role) {
            LOG.warn("frontend {} asked to join as {} but is registered as {}, keeping {}",
                    node, role, fe.getRole(), fe.getRole());
        } else {
            LOG.info("frontend {} is already registered as {} ({}), it rejoins with its old name",
                    node, fe.getRole(), fe.getNodeName());
        }
        return new FrontendJoinResult(fe.getRole(), fe.getNodeName(), HostPort.of(nodeMgr.getSelfNode()),
                String.valueOf(nodeMgr.getClusterId()), existed);
    }

    /**
     * Registers a CN. A node already registered with the same host and heartbeat port is returned as is.
     */
    public ComputeNodeJoinResult joinComputeNode(HostPort node, String warehouse, String cnGroup) throws JoinException {
        ComputeNode cn = systemInfo.getComputeNodeWithHeartbeatPort(node.host(), node.port());
        boolean existed = cn != null;
        if (cn == null) {
            String warehouseName = Strings.isNullOrEmpty(warehouse) ? WarehouseManager.DEFAULT_WAREHOUSE_NAME : warehouse;
            try {
                systemInfo.checkSameNodeExist(node.host(), node.port());
                systemInfo.addComputeNode(node.host(), node.port(), warehouseName, Strings.nullToEmpty(cnGroup));
            } catch (DdlException e) {
                throw new JoinException(STATUS_CONFLICT, e.getMessage());
            }
            cn = systemInfo.getComputeNodeWithHeartbeatPort(node.host(), node.port());
            if (cn == null) {
                throw new JoinException(STATUS_UNAVAILABLE, "compute node " + node + " was not registered, retry");
            }
            LOG.info("compute node {} joined with id {}", node, cn.getId());
        }
        return new ComputeNodeJoinResult(cn.getId(), warehouseName(cn, warehouse), existed);
    }

    private static String warehouseName(ComputeNode cn, String requested) {
        Warehouse wh = GlobalStateMgr.getCurrentState().getWarehouseMgr().getWarehouseAllowNull(cn.getWarehouseId());
        if (wh != null) {
            return wh.getName();
        }
        return Strings.isNullOrEmpty(requested) ? WarehouseManager.DEFAULT_WAREHOUSE_NAME : requested;
    }

    private static boolean isRetryable(DdlException e) {
        return e.getMessage() != null && e.getMessage().contains("Try again");
    }
}
