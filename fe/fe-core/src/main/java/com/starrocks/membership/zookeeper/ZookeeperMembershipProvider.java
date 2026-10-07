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
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Membership provider that keeps the cluster state in ZooKeeper under {@code fe_membership_zookeeper_root}:
 * <pre>
 *   cluster_id                    persistent, written by the leader after a bootstrap
 *   starting/&lt;host:port&gt;-&lt;seq&gt;     ephemeral, one per starting FE
 *   frontends/&lt;host:port&gt;          ephemeral, written by a ready FE in announce
 *   compute_nodes/&lt;host:port&gt;      persistent, the desired CN set an operator maintains
 *   token                          persistent, the join token shared through zookeeper when
 *                                  the ACL is open and fe.conf carries no secret
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
    private static final String TOKEN = "token";

    private static final int CONNECTION_TIMEOUT_MS = 10_000;
    private static final int CONNECT_WAIT_SECONDS = 30;
    private static final long ENSURE_INTERVAL_MS = 30_000;

    private MembershipContext ctx;
    private String root;
    private CuratorFramework client;
    private volatile String startingNode;
    private volatile MemberInfo announced;
    private volatile String tokenValue;
    private volatile Runnable onChange;
    private CuratorCache watcher;
    private long ensureIntervalMs = ENSURE_INTERVAL_MS;
    private ScheduledExecutorService ensureScheduler;
    private List<ACL> aclList = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private boolean creatorAcl;
    private String[] sessionAuthForTest;

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
        while (this.root.endsWith("/")) {
            this.root = this.root.substring(0, this.root.length() - 1);
        }
        if (!this.root.startsWith("/")) {
            throw new MembershipException(
                    "fe_membership_zookeeper_root must be an absolute znode path, got '" + this.root + "'");
        }
        this.aclList = parseAcl(Config.fe_membership_zookeeper_acl);
        this.creatorAcl = this.aclList == ZooDefs.Ids.CREATOR_ALL_ACL;
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
                .connectString(servers)
                .sessionTimeoutMs(Config.fe_membership_zookeeper_session_timeout_ms)
                .connectionTimeoutMs(CONNECTION_TIMEOUT_MS)
                .retryPolicy(new ExponentialBackoffRetry(1000, 8));
        if (sessionAuthForTest != null) {
            builder.authorization(sessionAuthForTest[0],
                    sessionAuthForTest[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        client = builder.build();
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
            if (creatorAcl) {
                verifyAncestorsProtected();
            }
            ensureRoot();
            ensureBranch(starting());
            registerStarting();
        } catch (Exception e) {
            client.close();
            if (e instanceof MembershipException failure) {
                throw withAclHint(failure);
            }
            throw withAclHint(new MembershipException(
                    "zookeeper membership provider failed to start: " + e.getMessage(), e));
        }
        ensureScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zookeeper-membership-ensure");
            thread.setDaemon(true);
            return thread;
        });
        ensureScheduler.scheduleWithFixedDelay(this::ensureRegisteredQuietly,
                ensureIntervalMs, ensureIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("zookeeper membership: self {} registered under {} on {} (session timeout {} ms)",
                ctx.self(), root, servers, negotiatedSessionTimeoutMs());
    }

    private static List<ACL> parseAcl(String value) throws MembershipException {
        String acl = Strings.nullToEmpty(value).trim().toLowerCase(Locale.ROOT);
        return switch (acl) {
            case "", "none" -> ZooDefs.Ids.OPEN_ACL_UNSAFE;
            case "sasl" -> ZooDefs.Ids.CREATOR_ALL_ACL;
            default -> throw new MembershipException(
                    "fe_membership_zookeeper_acl must be none or sasl, got '" + value + "'");
        };
    }

    /**
     * An ACL failure while the creator ACL is on means the session is not authenticated, or is
     * authenticated as a different principal than the one that owns the membership tree.
     */
    private MembershipException withAclHint(MembershipException failure) {
        if (!creatorAcl || !(failure.getCause() instanceof KeeperException keeper)) {
            return failure;
        }
        return switch (keeper.code()) {
            case INVALIDACL, NOAUTH, AUTHFAILED -> new MembershipException(failure.getMessage()
                    + " (fe_membership_zookeeper_acl=sasl needs a zookeeper session authenticated as the"
                    + " principal that owns the membership tree)", failure);
            default -> failure;
        };
    }

    /**
     * Replaces ensureRoot and ensureBranch: under the creator ACL the parent chain of the root must
     * already exist and must not be world-writable. ZooKeeper checks DELETE against the parent, so an
     * open ancestor would leave the whole membership tree deletable by any client.
     */
    private void verifyAncestorsProtected() throws MembershipException {
        String path = root.substring(0, root.lastIndexOf('/'));
        if (path.isEmpty()) {
            path = "/";
        }
        while (true) {
            try {
                for (ACL acl : client.getACL().forPath(path)) {
                    if ("world".equals(acl.getId().getScheme())) {
                        throw new MembershipException("zookeeper membership: ancestor " + path + " of " + root
                                + " is open to every client: protect the parent chain of"
                                + " fe_membership_zookeeper_root before enabling fe_membership_zookeeper_acl=sasl");
                    }
                }
            } catch (KeeperException.NoNodeException e) {
                throw new MembershipException("zookeeper membership: ancestor " + path + " of " + root
                        + " does not exist: pre-create the parent chain of fe_membership_zookeeper_root"
                        + " with the FE principal before enabling fe_membership_zookeeper_acl=sasl");
            } catch (KeeperException.NoAuthException e) {
                // an ancestor this session cannot even read is protected, which is what we need
            } catch (MembershipException e) {
                throw e;
            } catch (Exception e) {
                throw new MembershipException("cannot inspect the ACL of " + path + ": " + e.getMessage(), e);
            }
            if (path.equals("/")) {
                return;
            }
            path = path.substring(0, path.lastIndexOf('/'));
            if (path.isEmpty()) {
                path = "/";
            }
        }
    }

    /**
     * Creates the root with the configured ACL. creatingParentsIfNeeded() would create missing
     * parents open, so under the creator ACL the chain is verified to exist and to be protected first.
     */
    private void ensureRoot() throws MembershipException {
        try {
            if (creatorAcl) {
                client.create().withACL(aclList).forPath(root);
            } else {
                client.create().creatingParentsIfNeeded().withACL(aclList).forPath(root);
            }
        } catch (KeeperException.NodeExistsException e) {
            verifyProtected(root);
        } catch (Exception e) {
            throw new MembershipException("cannot create " + root + ": " + e.getMessage(), e);
        }
    }

    /** Creates a branch of the membership tree with the configured ACL when it does not exist yet. */
    private void ensureBranch(String path) throws MembershipException {
        try {
            client.create().withACL(aclList).forPath(path);
        } catch (KeeperException.NodeExistsException e) {
            verifyProtected(path);
        } catch (Exception e) {
            throw new MembershipException("cannot create " + path + ": " + e.getMessage(), e);
        }
    }

    /** Rejects a node that already exists with an open ACL instead of protecting it halfway. */
    private void verifyProtected(String path) throws MembershipException {
        if (!creatorAcl) {
            return;
        }
        try {
            for (ACL acl : client.getACL().forPath(path)) {
                if ("world".equals(acl.getId().getScheme())) {
                    throw new MembershipException("zookeeper membership: " + path
                            + " exists with an ACL open to every client: recreate it as the FE principal"
                            + " or keep fe_membership_zookeeper_acl=none");
                }
            }
        } catch (KeeperException.NoAuthException e) {
            // owned by another principal: the following create fails with the acl hint
        } catch (MembershipException e) {
            throw e;
        } catch (Exception e) {
            throw new MembershipException("cannot inspect the ACL of " + path + ": " + e.getMessage(), e);
        }
    }

    /** (Re-)creates the ephemeral registration; a re-registration after a lost session gets a new seq. */
    private synchronized void registerStarting() throws MembershipException {
        String path = call(() -> client.create()
                .withMode(CreateMode.EPHEMERAL_SEQUENTIAL)
                .withACL(aclList)
                .forPath(starting() + "/" + ctx.self() + "-"));
        startingNode = path.substring(path.lastIndexOf('/') + 1);
    }

    @VisibleForTesting
    void setSessionAuthForTest(String scheme, String auth) {
        this.sessionAuthForTest = new String[] {scheme, auth};
    }

    private void reRegisterAfterSessionLoss() {
        try {
            ensureRegistered();
        } catch (MembershipException e) {
            LOG.warn("zookeeper membership: failed to re-register after reconnect: {}", e.getMessage());
        }
    }

    /** Periodic self-heal: recreates the own ephemerals without any provider call from the outside. */
    private void ensureRegisteredQuietly() {
        try {
            ensureRegistered();
        } catch (Exception e) {
            LOG.warn("zookeeper membership: periodic re-registration failed: {}", e.getMessage());
        }
    }

    @VisibleForTesting
    void setEnsureIntervalForTest(long ensureIntervalMs) {
        this.ensureIntervalMs = ensureIntervalMs;
    }

    private int negotiatedSessionTimeoutMs() {
        try {
            return client.getZookeeperClient().getZooKeeper().getSessionTimeout();
        } catch (Exception e) {
            return Config.fe_membership_zookeeper_session_timeout_ms;
        }
    }

    /** Recreates our own ephemerals when they are gone, e.g. after a lost session. */
    private synchronized void ensureRegistered() throws MembershipException {
        if (startingNode == null || !exists(starting() + "/" + startingNode)) {
            ensureRoot();
            ensureBranch(starting());
            registerStarting();
        }
        MemberInfo self = announced;
        if (self != null && !exists(frontends() + "/" + self.hostPort())) {
            announceSelf();
        }
        ensureToken();
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
        ensureToken();
        List<HostPort> seeds = new ArrayList<>();
        for (String child : childrenOf(frontends())) {
            try {
                seeds.add(HostPort.parse(child));
            } catch (IllegalArgumentException e) {
                LOG.warn("zookeeper membership: skipping announced frontend with unreadable name '{}'", child);
            }
        }
        seeds.sort(Comparator.comparing(HostPort::host).thenComparing(HostPort::port));
        return seeds;
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

    private String tokenPath() {
        return root + "/" + TOKEN;
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
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                client.create().withACL(aclList)
                        .forPath(clusterId(), clusterId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                LOG.info("zookeeper membership: recorded cluster id {} in {}", clusterId, clusterId());
                return;
            } catch (KeeperException.NodeExistsException e) {
                String recorded = readClusterId();
                if (!clusterId.equals(recorded)) {
                    throw new MembershipException("cluster " + recorded + " is already recorded in " + clusterId()
                            + ", refusing to record " + clusterId + ": check fe_membership_zookeeper_root");
                }
                return;
            } catch (KeeperException.NoNodeException e) {
                // the tree was deleted under us: recreate the root and retry once
                ensureRoot();
            } catch (Exception e) {
                throw new MembershipException("cannot record cluster id in " + clusterId() + ": " + e.getMessage(), e);
            }
        }
        throw new MembershipException("cannot record cluster id in " + clusterId() + ": the root keeps disappearing");
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
        ensureBranch(frontends());
        try {
            client.create().withMode(CreateMode.EPHEMERAL).withACL(aclList)
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

    /**
     * With an open ACL there is no membership proof, so the join token is shared through the
     * {@code token} znode and fe.conf carries no secret. Under the creator ACL the live
     * registration in starting/ already proves membership and no secret is needed at all.
     */
    @Override
    public boolean providesToken() {
        return requiresToken();
    }

    @Override
    public void publishToken(String token) throws MembershipException {
        tokenValue = token;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                client.create().withACL(aclList)
                        .forPath(tokenPath(), token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                LOG.info("zookeeper membership: published the cluster token in {}", tokenPath());
                return;
            } catch (KeeperException.NodeExistsException e) {
                // the record exists and is never overwritten; a disagreement with this FE's
                // token means a poisoned record or two clusters sharing one root
                String recorded = readToken();
                if (recorded != null && !recorded.equals(token)) {
                    LOG.warn("zookeeper membership: token record in {} differs from this cluster's"
                            + " token, keeping the record", tokenPath());
                }
                return;
            } catch (KeeperException.NoNodeException e) {
                // the tree was deleted under us: recreate the root and retry once
                ensureRoot();
            } catch (Exception e) {
                throw new MembershipException(
                        "cannot publish the cluster token in " + tokenPath() + ": " + e.getMessage(), e);
            }
        }
        throw new MembershipException(
                "cannot publish the cluster token in " + tokenPath() + ": the root keeps disappearing");
    }

    @Override
    public String readToken() throws MembershipException {
        try {
            return new String(client.getData().forPath(tokenPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (KeeperException.NoNodeException e) {
            return null;
        } catch (Exception e) {
            throw new MembershipException("cannot read " + tokenPath() + ": " + e.getMessage(), e);
        }
    }

    /** Re-publishes our token record when the znode is gone; a no-op when we published none. */
    private void ensureToken() throws MembershipException {
        String token = tokenValue;
        if (token == null || exists(tokenPath())) {
            return;
        }
        publishToken(token);
    }

    /**
     * Under the creator ACL only a session of the FE principal can hold a registration under
     * starting/, so a live node there is proof that the address belongs to this cluster.
     */
    @Override
    public boolean vouches(HostPort node) throws MembershipException {
        if (!creatorAcl) {
            return false;
        }
        for (String child : childrenOf(starting())) {
            int dash = child.lastIndexOf('-');
            try {
                if (dash > 0 && HostPort.parse(child.substring(0, dash)).sameNode(node)) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                LOG.warn("zookeeper membership: unreadable starting child '{}'", child);
            }
        }
        return false;
    }

    /**
     * Answers before start(), so MembershipProviders can drop the token requirement: reads the
     * config directly instead of the field start() fills in.
     */
    @Override
    public boolean requiresToken() {
        return !"sasl".equals(Strings.nullToEmpty(Config.fe_membership_zookeeper_acl).trim()
                .toLowerCase(Locale.ROOT));
    }

    /** Wire format of a znode under {@code frontends/}. */
    record FrontendPayload(String role, String nodeName, String clusterId) {
    }

    @Override
    public Optional<Set<ComputeNodeSpec>> expectedComputeNodes() throws MembershipException {
        ensureRegistered();
        ensureToken();
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

    /**
     * Watches the membership branches and fires the listener on every change of an announced frontend
     * or a desired compute node, so the reconciler runs without waiting for its interval.
     */
    @Override
    public synchronized void addChangeListener(Runnable onChange) {
        this.onChange = onChange;
        if (watcher != null) {
            return;
        }
        watcher = CuratorCache.build(client, root);
        watcher.listenable().addListener(CuratorCacheListener.builder()
                .forCreates(child -> membershipChanged(child.getPath()))
                .forChanges((previous, node) -> membershipChanged(node.getPath()))
                .forDeletes(child -> membershipChanged(child.getPath()))
                .build());
        watcher.start();
        LOG.info("zookeeper membership: watching for changes under {}", root);
    }

    private void membershipChanged(String path) {
        Runnable listener = onChange;
        if (listener == null) {
            return;
        }
        if (path.startsWith(frontends() + "/") || path.startsWith(computeNodes() + "/")) {
            listener.run();
        }
    }

    @Override
    public synchronized void close() {
        if (ensureScheduler != null) {
            ensureScheduler.shutdownNow();
            ensureScheduler = null;
        }
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        if (client != null) {
            startingNode = null;
            client.close();
            client = null;
        }
    }
}
