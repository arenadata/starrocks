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
import com.google.common.base.Strings;
import com.starrocks.common.Config;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.server.RunMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Startup protocol of an FE with empty meta: ask the seeds for the leader and register through the
 * membership API, or bootstrap a new cluster when the provider allows it. Waits as long as it takes;
 * only configuration errors (bad token, host conflict, wrong run mode) abort the start.
 */
public final class MembershipJoiner implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger(MembershipJoiner.class);

    /** What a seed reports about the leader. */
    public record LeaderInfo(HostPort leader, int leaderHttpPort, String clusterId, String runMode) {
    }

    /** Answer of the leader to a join request: HTTP status plus the fields of a successful answer. */
    public record JoinOutcome(int status, String message, FrontendNodeType role, String nodeName, HostPort helper) {
        public static JoinOutcome failed(int status, String message) {
            return new JoinOutcome(status, message, null, null, null);
        }

        public boolean ok() {
            return status == 200;
        }
    }

    /** Bootstrap a new cluster, or join through the given helper. */
    public record Decision(boolean bootstrap, HostPort helper) {
        public static Decision ofBootstrap() {
            return new Decision(true, null);
        }

        public static Decision ofJoin(HostPort helper) {
            return new Decision(false, Objects.requireNonNull(helper));
        }
    }

    /** Transport to seeds and the leader; a fake in tests. */
    public interface LeaderClient {
        /** Empty when the seed answers but has no leader. IOException when it does not answer. */
        Optional<LeaderInfo> leader(HostPort seed) throws IOException;

        /** The token is what the joiner resolved for this request; null sends no token header. */
        JoinOutcome join(HostPort leaderHttp, HostPort self, FrontendNodeType role, String token)
                throws IOException;
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static volatile LeaderClient clientOverride;

    private final MembershipProvider provider;
    private final MembershipContext ctx;
    private final List<HostPort> extraSeeds;
    private final LeaderClient client;
    private final Sleeper sleeper;
    private final BooleanSupplier initialStateNew;

    public MembershipJoiner(MembershipProvider provider, MembershipContext ctx, List<HostPort> extraSeeds,
                            LeaderClient client, Sleeper sleeper, BooleanSupplier initialStateNew) {
        this.provider = Objects.requireNonNull(provider);
        this.ctx = Objects.requireNonNull(ctx);
        this.extraSeeds = List.copyOf(extraSeeds);
        this.client = Objects.requireNonNull(client);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.initialStateNew = Objects.requireNonNull(initialStateNew);
    }

    /**
     * Joiner of the running FE: the configured provider plus the --helper addresses as extra seeds.
     */
    public static MembershipJoiner forStartup(List<HostPort> helperSeeds) {
        MembershipProvider provider = MembershipProviders.current()
                .orElseThrow(() -> new IllegalStateException("membership provider is not configured"));
        MembershipContext ctx = MembershipProviders.context().orElseThrow();
        LeaderClient client = clientOverride != null ? clientOverride : new HttpLeaderClient();
        return new MembershipJoiner(provider, ctx, helperSeeds, client, Thread::sleep,
                MembershipProviders::isInitialStateNew);
    }

    @VisibleForTesting
    public static void setClientForTest(LeaderClient client) {
        clientOverride = client;
    }

    /** Releases the leader client when it holds resources, e.g. the HTTP client of forStartup. */
    @Override
    public void close() {
        if (client instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.warn("failed to close the leader client: {}", e.getMessage());
            }
        }
    }

    /** Blocks until the node has joined or may bootstrap. */
    public Decision resolve() throws MembershipException {
        for (int attempt = 1; ; attempt++) {
            Decision decision = attempt(attempt);
            if (decision != null) {
                return decision;
            }
            try {
                sleeper.sleep(Config.fe_membership_retry_interval_seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MembershipException("interrupted while waiting for the leader", e);
            }
        }
    }

    /** One round of the protocol. Null means: nothing decided yet, try again later. */
    @VisibleForTesting
    Decision attempt(int attempt) throws MembershipException {
        List<HostPort> seeds = seeds();
        LeaderInfo leader = findLeader(seeds);
        if (leader != null) {
            return join(leader);
        }
        Optional<String> recorded = provider.existingClusterId();
        if (recorded.isPresent()) {
            LOG.warn("attempt {}: cluster {} is recorded but no leader answered among {}, waiting",
                    attempt, recorded.get(), seeds);
            return null;
        }
        if (!initialStateNew.getAsBoolean()) {
            LOG.warn("attempt {}: no leader among {} and fe_cluster_initial_state is not new, waiting", attempt, seeds);
            return null;
        }
        if (provider.isBootstrapCandidate()) {
            LOG.info("no leader among {} and this FE is the bootstrap candidate, creating a new cluster", seeds);
            return Decision.ofBootstrap();
        }
        LOG.warn("attempt {}: no leader among {}, waiting for the bootstrap candidate", attempt, seeds);
        return null;
    }

    private List<HostPort> seeds() {
        List<HostPort> seeds = new ArrayList<>();
        try {
            for (HostPort seed : provider.seeds()) {
                addSeed(seeds, seed);
            }
        } catch (MembershipException e) {
            LOG.warn("provider {} cannot list seeds: {}", provider.name(), e.getMessage());
        }
        for (HostPort seed : extraSeeds) {
            addSeed(seeds, seed);
        }
        return seeds;
    }

    private void addSeed(List<HostPort> seeds, HostPort seed) {
        if (!seeds.contains(seed) && !seed.sameNode(ctx.self())) {
            seeds.add(seed);
        }
    }

    private LeaderInfo findLeader(List<HostPort> seeds) {
        for (HostPort seed : seeds) {
            try {
                Optional<LeaderInfo> info = client.leader(seed);
                if (info.isPresent()) {
                    return info.get();
                }
                LOG.info("seed {} has no leader yet", seed);
            } catch (IOException e) {
                LOG.info("seed {} did not answer: {}", seed, e.getMessage());
            }
        }
        return null;
    }

    private Decision join(LeaderInfo leader) throws MembershipException {
        if (!leader.runMode().equalsIgnoreCase(RunMode.name())) {
            throw new MembershipException("cluster run_mode " + leader.runMode()
                    + " differs from the configured " + RunMode.name());
        }
        HostPort leaderHttp = new HostPort(leader.leader().host(), leader.leaderHttpPort());
        String token = effectiveToken();
        JoinOutcome outcome;
        try {
            outcome = client.join(leaderHttp, ctx.self(), ctx.desiredRole(), token);
        } catch (IOException e) {
            LOG.warn("join request to leader {} failed: {}", leaderHttp, e.getMessage());
            return null;
        }
        if (outcome.ok()) {
            LOG.info("joined cluster {} through leader {} as {} ({}), helper {}",
                    leader.clusterId(), leader.leader(), outcome.role(), outcome.nodeName(), outcome.helper());
            return Decision.ofJoin(outcome.helper());
        }
        return switch (outcome.status()) {
            case 404 -> {
                LOG.warn("leader {} has no membership API; run ALTER SYSTEM ADD {} \"{}\" there, "
                        + "this FE keeps waiting with it as helper", leader.leader(), ctx.desiredRole(), ctx.self());
                yield Decision.ofJoin(leader.leader());
            }
            case 401 -> {
                if (token == null) {
                    // nothing was sent, so no fe.conf value can fix this: the token record or the
                    // membership proof may simply not have reached the leader yet
                    LOG.warn("leader {} rejected the tokenless join ({}), retrying",
                            leader.leader(), outcome.message());
                    yield null;
                }
                throw new MembershipException("leader " + leader.leader()
                        + " rejected the join request: " + outcome.status() + " " + outcome.message());
            }
            case 400, 403, 409 -> throw new MembershipException("leader " + leader.leader()
                    + " rejected the join request: " + outcome.status() + " " + outcome.message());
            default -> {
                LOG.warn("leader {} answered {} {} to the join request, retrying",
                        leader.leader(), outcome.status(), outcome.message());
                yield null;
            }
        };
    }

    /**
     * Token for the join request: the fe.conf value when set, otherwise the token the provider
     * serves, e.g. from its backend. A provider read failure is not fatal — the tokenless join
     * is retried and the next round re-reads.
     */
    private String effectiveToken() {
        if (!Strings.isNullOrEmpty(Config.auth_token)) {
            return Config.auth_token;
        }
        if (!provider.providesToken()) {
            return null;
        }
        try {
            return provider.readToken();
        } catch (MembershipException e) {
            LOG.warn("provider {} cannot read the join token yet: {}", provider.name(), e.getMessage());
            return null;
        }
    }
}
