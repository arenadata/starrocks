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

import com.google.common.annotations.VisibleForTesting;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.util.FrontendDaemon;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.NodeMgr;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.Frontend;
import com.starrocks.system.SystemInfoService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Leader-side daemon that converges the registered membership towards what the provider desires:
 * registers desired CNs that are missing, and drops FEs and CNs that are not desired once they have been
 * dead for the grace period. Live nodes are never dropped, and at most one node is dropped per round.
 */
public class MembershipReconciler extends FrontendDaemon {
    private static final Logger LOG = LogManager.getLogger(MembershipReconciler.class);

    private final MembershipProvider provider;
    private final NodeMgr nodeMgr;
    private final SystemInfoService systemInfo;
    private final LongSupplier clock;
    private final Map<HostPort, Long> frontendDeadSince = new HashMap<>();
    private final Map<HostPort, Long> computeNodeDeadSince = new HashMap<>();

    public MembershipReconciler(MembershipProvider provider, NodeMgr nodeMgr, SystemInfoService systemInfo,
                                LongSupplier clock) {
        super("membership reconciler", Config.fe_membership_reconcile_interval_seconds * 1000L);
        this.provider = provider;
        this.nodeMgr = nodeMgr;
        this.systemInfo = systemInfo;
        this.clock = clock;
    }

    public static MembershipReconciler forCurrentState(MembershipProvider provider) {
        NodeMgr nodeMgr = GlobalStateMgr.getCurrentState().getNodeMgr();
        return new MembershipReconciler(provider, nodeMgr, nodeMgr.getClusterInfo(), System::currentTimeMillis);
    }

    @Override
    protected void runAfterCatalogReady() {
        setInterval(Config.fe_membership_reconcile_interval_seconds * 1000L);
        if (!GlobalStateMgr.getCurrentState().isLeader()) {
            return;
        }
        reconcile();
    }

    @VisibleForTesting
    void reconcile() {
        try {
            reconcileFrontends();
        } catch (Exception e) {
            LOG.warn("frontend membership reconciliation failed", e);
        }
        try {
            reconcileComputeNodes();
        } catch (Exception e) {
            LOG.warn("compute node membership reconciliation failed", e);
        }
    }

    @VisibleForTesting
    void reconcileFrontends() throws MembershipException {
        if (!Config.fe_membership_auto_drop_fe) {
            return;
        }
        Optional<Set<FrontendSpec>> expected = provider.expectedFrontends();
        if (expected.isEmpty()) {
            return;
        }
        Map<HostPort, FrontendSpec> desired = new HashMap<>();
        expected.get().forEach(spec -> desired.put(spec.hostPort(), spec));
        HostPort self = HostPort.of(nodeMgr.getSelfNode());
        Set<HostPort> present = new HashSet<>();

        for (Frontend fe : nodeMgr.getAllFrontends()) {
            if (fe.getEditLogPort() <= 0) {
                continue;
            }
            HostPort node = new HostPort(fe.getHost(), fe.getEditLogPort());
            present.add(node);
            if (node.sameNode(self)) {
                continue;
            }
            FrontendSpec spec = find(desired, node);
            if (spec != null) {
                frontendDeadSince.remove(node);
                if (spec.role() != fe.getRole()) {
                    LOG.warn("frontend {} is registered as {} but desired as {}; change the role by dropping "
                            + "and re-adding it", node, fe.getRole(), spec.role());
                }
                continue;
            }
            if (!readyToDrop(frontendDeadSince, node, fe.isAlive(), fe.getLastUpdateTime(), "frontend")) {
                continue;
            }
            try {
                nodeMgr.dropFrontend(fe.getRole(), fe.getHost(), fe.getEditLogPort());
                frontendDeadSince.remove(node);
                LOG.info("dropped frontend {} ({}): not desired and dead beyond the grace period", node, fe.getRole());
            } catch (DdlException e) {
                LOG.warn("failed to drop frontend {}: {}", node, e.getMessage());
            }
            return;
        }
        frontendDeadSince.keySet().retainAll(present);
    }

    @VisibleForTesting
    void reconcileComputeNodes() throws MembershipException {
        if (!Config.fe_membership_auto_add_cn && !Config.fe_membership_auto_drop_cn) {
            return;
        }
        Optional<Set<ComputeNodeSpec>> expected = provider.expectedComputeNodes();
        if (expected.isEmpty()) {
            return;
        }
        Map<HostPort, ComputeNodeSpec> desired = new HashMap<>();
        expected.get().forEach(spec -> desired.put(spec.hostPort(), spec));
        List<ComputeNode> current = systemInfo.getComputeNodes();

        if (Config.fe_membership_auto_add_cn) {
            for (ComputeNodeSpec spec : expected.get()) {
                boolean registered = current.stream()
                        .anyMatch(cn -> new HostPort(cn.getHost(), cn.getHeartbeatPort()).sameNode(spec.hostPort()));
                if (registered) {
                    continue;
                }
                try {
                    systemInfo.checkSameNodeExist(spec.hostPort().host(), spec.hostPort().port());
                    systemInfo.addComputeNode(spec.hostPort().host(), spec.hostPort().port(), spec.warehouse(),
                            spec.cnGroup());
                    LOG.info("added compute node {} from the desired membership", spec);
                } catch (DdlException e) {
                    LOG.warn("failed to add compute node {}: {}", spec, e.getMessage());
                }
            }
        }

        if (!Config.fe_membership_auto_drop_cn) {
            return;
        }
        Set<HostPort> present = new HashSet<>();
        for (ComputeNode cn : current) {
            if (cn.getHeartbeatPort() <= 0) {
                continue;
            }
            HostPort node = new HostPort(cn.getHost(), cn.getHeartbeatPort());
            present.add(node);
            if (find(desired, node) != null) {
                computeNodeDeadSince.remove(node);
                continue;
            }
            if (!readyToDrop(computeNodeDeadSince, node, cn.isAlive(), cn.getLastUpdateMs(), "compute node")) {
                continue;
            }
            try {
                systemInfo.dropComputeNode(cn.getHost(), cn.getHeartbeatPort(), "", "");
                computeNodeDeadSince.remove(node);
                LOG.info("dropped compute node {}: not desired and dead beyond the grace period", node);
            } catch (DdlException e) {
                LOG.warn("failed to drop compute node {}: {}", node, e.getMessage());
            }
            return;
        }
        computeNodeDeadSince.keySet().retainAll(present);
    }

    /**
     * Tracks since when an undesired node is dead. A node that never heartbeated counts from the first
     * observation, so a joiner that is still starting gets the full grace period.
     */
    private boolean readyToDrop(Map<HostPort, Long> deadSince, HostPort node, boolean alive, long lastAliveMs,
                                String kind) {
        long now = clock.getAsLong();
        if (alive) {
            deadSince.remove(node);
            LOG.info("{} {} is not desired but alive, keeping it", kind, node);
            return false;
        }
        long since = deadSince.computeIfAbsent(node, k -> lastAliveMs > 0 ? Math.min(lastAliveMs, now) : now);
        long graceMs = Config.fe_membership_drop_grace_seconds * 1000L;
        if (now - since < graceMs) {
            LOG.info("{} {} is not desired and dead for {} s, dropping after {} s",
                    kind, node, (now - since) / 1000, graceMs / 1000);
            return false;
        }
        return true;
    }

    private static <T> T find(Map<HostPort, T> desired, HostPort node) {
        T exact = desired.get(node);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<HostPort, T> entry : desired.entrySet()) {
            if (entry.getKey().sameNode(node)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
