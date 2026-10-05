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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.starrocks.common.Config;
import com.starrocks.common.security.KerberosLoginManager;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.ComputeNodeSpec;
import com.starrocks.membership.FrontendSpec;
import com.starrocks.membership.HostPort;
import com.starrocks.membership.MemberInfo;
import com.starrocks.membership.MembershipContext;
import com.starrocks.membership.MembershipException;
import com.starrocks.membership.MembershipProvider;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Membership provider that keeps the cluster state in ZooKeeper under {@code fe_membership_zookeeper_root}:
 * <pre>
 *   cluster_id                    persistent, written by the leader after a bootstrap
 *   starting/&lt;host:port&gt;-&lt;seq&gt;     ephemeral, one per starting FE
 *   frontends/&lt;host:port&gt;          ephemeral, written by a ready FE in announce
 *   compute_nodes/&lt;host:port&gt;      persistent, the desired CN set an operator maintains
 * </pre>
 * The FE that registered under {@code starting} first holds the smallest sequence number and is the
 * bootstrap candidate, so a cold start elects exactly one candidate without a configured node order.
 */
public class ZookeeperMembershipProvider implements MembershipProvider {
    private static final Logger LOG = LogManager.getLogger(ZookeeperMembershipProvider.class);

    public static final String NAME = "zookeeper";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String STARTING = "starting";
    private static final String FRONTENDS = "frontends";
    private static final String COMPUTE_NODES = "compute_nodes";
    private static final String CLUSTER_ID = "cluster_id";

    private static final int CONNECTION_TIMEOUT_MS = 10_000;
    private static final int CONNECT_WAIT_SECONDS = 30;

    private MembershipContext ctx;
    private String root;
    private CuratorFramework client;
    private volatile String startingNode;
    private volatile MemberInfo announced;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start(MembershipContext ctx) throws MembershipException {
        this.ctx = ctx;
        String servers = Strings.nullToEmpty(Config.fe_membership_zookeeper_servers).trim();
        if (servers.isEmpty()) {
            throw new MembershipException(
                    "fe_membership_provider=zookeeper requires fe_membership_zookeeper_servers to be set");
        }
        this.root = Strings.nullToEmpty(Config.fe_membership_zookeeper_root).trim();
        if (!this.root.startsWith("/")) {
            throw new MembershipException(
                    "fe_membership_zookeeper_root must be an absolute znode path, got '" + this.root + "'");
        }
        client = CuratorFrameworkFactory.newClient(servers,
                Config.fe_membership_zookeeper_session_timeout_ms, CONNECTION_TIMEOUT_MS,
                new ExponentialBackoffRetry(1000, 8));
        if (KerberosLoginManager.isLoggedIn()) {
            ZooKeeperSasl.installClientEntry(Config.kerberos_principal, Config.kerberos_keytab);
        }
        client.getConnectionStateListenable().addListener((c, state) -> {
            if (state == ConnectionState.RECONNECTED) {
                reRegisterAfterSessionLoss();
            }
        });
        client.start();
        try {
            if (!client.blockUntilConnected(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new MembershipException(
                        "cannot connect to zookeeper " + servers + " within " + CONNECT_WAIT_SECONDS + " s");
            }
            registerStarting();
        } catch (MembershipException e) {
            throw e;
        } catch (Exception e) {
            client.close();
            throw new MembershipException("zookeeper membership provider failed to start: " + e.getMessage(), e);
        }
        LOG.info("zookeeper membership: self {} registered under {} on {}", ctx.self(), root, servers);
    }

    /** (Re-)creates the ephemeral registration; a re-registration after a lost session gets a new seq. */
    private synchronized void registerStarting() throws MembershipException {
        String path = call(() -> client.create()
                .creatingParentsIfNeeded()
                .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                .forPath(starting() + "/" + ctx.self() + "-"));
        startingNode = path.substring(path.lastIndexOf('/') + 1);
    }

    private void reRegisterAfterSessionLoss() {
        try {
            startingNode = null;
            ensureRegistered();
        } catch (MembershipException e) {
            LOG.warn("zookeeper membership: failed to re-register after reconnect: {}", e.getMessage());
        }
    }

    /** Recreates our own ephemerals when they are gone, e.g. after a lost session. */
    private synchronized void ensureRegistered() throws MembershipException {
        if (startingNode == null || !exists(starting() + "/" + startingNode)) {
            registerStarting();
        }
        MemberInfo self = announced;
        if (self != null && !exists(frontends() + "/" + self.hostPort())) {
            announceSelf();
        }
    }

    private boolean exists(String path) throws MembershipException {
        try {
            return client.checkExists().forPath(path) != null;
        } catch (Exception e) {
            throw new MembershipException("cannot check " + path + ": " + e.getMessage(), e);
        }
    }

    @VisibleForTesting
    long sessionId() throws MembershipException {
        return call(() -> client.getZookeeperClient().getZooKeeper().getSessionId());
    }

    @Override
    public List<HostPort> seeds() throws MembershipException {
        ensureRegistered();
        return childrenOf(frontends()).stream().map(HostPort::parse).sorted().toList();
    }

    /** Children of a branch; a branch that does not exist yet is empty. */
    private List<String> childrenOf(String path) throws MembershipException {
        try {
            return client.getChildren().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            return List.of();
        } catch (Exception e) {
            throw new MembershipException("cannot list " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> existingClusterId() throws MembershipException {
        try {
            return Optional.of(readClusterId());
        } catch (MembershipException e) {
            if (e.getCause() instanceof KeeperException.NoNodeException) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public boolean isBootstrapCandidate() throws MembershipException {
        ensureRegistered();
        List<String> children = childrenOf(starting());
        String candidate = children.stream().min(ZookeeperMembershipProvider::compareSequence).orElse(null);
        return candidate != null && candidate.equals(startingNode);
    }

    /** Orders child names by the sequence number ZooKeeper appended after the last '-'. */
    static int compareSequence(String a, String b) {
        return Long.compare(sequence(a), sequence(b));
    }

    private static long sequence(String child) {
        try {
            return Long.parseLong(child.substring(child.lastIndexOf('-') + 1));
        } catch (NumberFormatException e) {
            LOG.warn("zookeeper membership: no sequence number in starting child '{}'", child);
            return Long.MAX_VALUE;
        }
    }

    private String starting() {
        return root + "/" + STARTING;
    }

    private String frontends() {
        return root + "/" + FRONTENDS;
    }

    private String computeNodes() {
        return root + "/" + COMPUTE_NODES;
    }

    private String clusterId() {
        return root + "/" + CLUSTER_ID;
    }

    /** Runs a Curator call and turns its failures into MembershipException. */
    private <T> T call(CuratorCall<T> op) throws MembershipException {
        try {
            return op.apply();
        } catch (MembershipException e) {
            throw e;
        } catch (Exception e) {
            throw new MembershipException("zookeeper " + root + " operation failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface CuratorCall<T> {
        T apply() throws Exception;
    }

    @Override
    public void recordClusterId(String clusterId) throws MembershipException {
        try {
            client.create().creatingParentsIfNeeded()
                    .forPath(clusterId(), clusterId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LOG.info("zookeeper membership: recorded cluster id {} in {}", clusterId, clusterId());
        } catch (KeeperException.NodeExistsException e) {
            String recorded = readClusterId();
            if (!clusterId.equals(recorded)) {
                throw new MembershipException("cluster " + recorded + " is already recorded in " + clusterId()
                        + ", refusing to record " + clusterId + ": check fe_membership_zookeeper_root");
            }
        } catch (Exception e) {
            throw new MembershipException("cannot record cluster id in " + clusterId() + ": " + e.getMessage(), e);
        }
    }

    private String readClusterId() throws MembershipException {
        try {
            return new String(client.getData().forPath(clusterId()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new MembershipException("cannot read " + clusterId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void announce(MemberInfo self) throws MembershipException {
        announced = self;
        announceSelf();
    }

    /** (Re-)creates the ephemeral frontend node of the announced self. */
    private synchronized void announceSelf() throws MembershipException {
        MemberInfo self = announced;
        if (self == null) {
            return;
        }
        String path = frontends() + "/" + self.hostPort();
        try {
            client.delete().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            // no stale node to replace
        } catch (Exception e) {
            throw new MembershipException("cannot replace " + path + ": " + e.getMessage(), e);
        }
        try {
            client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL)
                    .forPath(path, JSON.writeValueAsBytes(
                            new FrontendPayload(self.role().name(), self.nodeName(), self.clusterId())));
            LOG.info("zookeeper membership: announced {} in {}", self, path);
        } catch (Exception e) {
            throw new MembershipException("cannot announce in " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Set<FrontendSpec>> expectedFrontends() throws MembershipException {
        ensureRegistered();
        List<String> children;
        try {
            children = client.getChildren().forPath(frontends());
        } catch (KeeperException.NoNodeException e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new MembershipException("cannot list " + frontends() + ": " + e.getMessage(), e);
        }
        Set<FrontendSpec> desired = new HashSet<>();
        for (String child : children) {
            FrontendSpec spec = readFrontendSpec(frontends() + "/" + child, child);
            if (spec != null) {
                desired.add(spec);
            }
        }
        return Optional.of(desired);
    }

    /** Parses one announced frontend; a node that vanished or carries garbage is skipped with a warning. */
    private FrontendSpec readFrontendSpec(String path, String child) {
        byte[] data;
        try {
            data = client.getData().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            return null;
        } catch (Exception e) {
            LOG.warn("zookeeper membership: cannot read announced frontend {}: {}", path, e.getMessage());
            return null;
        }
        try {
            FrontendPayload payload = JSON.readValue(data, FrontendPayload.class);
            return new FrontendSpec(HostPort.parse(child), FrontendNodeType.valueOf(payload.role()));
        } catch (Exception e) {
            LOG.warn("zookeeper membership: skipping announced frontend {} with unreadable payload: {}",
                    path, e.getMessage());
            return null;
        }
    }

    /** Wire format of a znode under {@code frontends/}. */
    record FrontendPayload(String role, String nodeName, String clusterId) {
    }

    @Override
    public Optional<Set<ComputeNodeSpec>> expectedComputeNodes() throws MembershipException {
        ensureRegistered();
        List<String> children;
        try {
            children = client.getChildren().forPath(computeNodes());
        } catch (KeeperException.NoNodeException e) {
            return Optional.empty();
        } catch (Exception e) {
            throw new MembershipException("cannot list " + computeNodes() + ": " + e.getMessage(), e);
        }
        Set<ComputeNodeSpec> desired = new HashSet<>();
        for (String child : children) {
            ComputeNodeSpec spec = readComputeNodeSpec(computeNodes() + "/" + child, child);
            if (spec != null) {
                desired.add(spec);
            }
        }
        return Optional.of(desired);
    }

    /** Parses one desired compute node; an empty payload means the default warehouse and no CN group. */
    private ComputeNodeSpec readComputeNodeSpec(String path, String child) {
        byte[] data;
        try {
            data = client.getData().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            return null;
        } catch (Exception e) {
            LOG.warn("zookeeper membership: cannot read desired compute node {}: {}", path, e.getMessage());
            return null;
        }
        try {
            ComputeNodePayload payload = data.length == 0 ? new ComputeNodePayload(null, null)
                    : JSON.readValue(data, ComputeNodePayload.class);
            return new ComputeNodeSpec(HostPort.parse(child), payload.warehouse(), payload.cnGroup());
        } catch (Exception e) {
            LOG.warn("zookeeper membership: skipping desired compute node {} with unreadable payload: {}",
                    path, e.getMessage());
            return null;
        }
    }

    /** Wire format of a znode under {@code compute_nodes/}. */
    record ComputeNodePayload(String warehouse, String cnGroup) {
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            startingNode = null;
            client.close();
            client = null;
        }
    }
}
