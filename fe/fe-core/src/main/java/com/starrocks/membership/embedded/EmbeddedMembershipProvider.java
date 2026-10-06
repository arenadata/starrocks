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

package com.starrocks.membership.embedded;

import com.google.common.base.Strings;
import com.starrocks.common.Config;
import com.starrocks.membership.ComputeNodeSpec;
import com.starrocks.membership.FrontendSpec;
import com.starrocks.membership.HostPort;
import com.starrocks.membership.MemberInfo;
import com.starrocks.membership.MembershipContext;
import com.starrocks.membership.MembershipException;
import com.starrocks.membership.MembershipProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Provider without external dependencies: seeds come from fe_seed_nodes and an optional DNS name,
 * the desired membership from an optional file. It keeps no record of the cluster, so bootstrap
 * protection relies on fe_cluster_initial_state alone.
 */
public class EmbeddedMembershipProvider implements MembershipProvider {
    private static final Logger LOG = LogManager.getLogger(EmbeddedMembershipProvider.class);

    public static final String NAME = "embedded";

    private MembershipContext ctx;
    private List<HostPort> staticSeeds = new ArrayList<>();
    private String seedDns = "";
    private Path membersFile;
    private volatile MembersFile lastAccepted;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void start(MembershipContext ctx) throws MembershipException {
        this.ctx = ctx;
        this.staticSeeds = parseSeeds(Config.fe_seed_nodes);
        this.seedDns = Strings.nullToEmpty(Config.fe_membership_embedded_seed_dns).trim();
        String file = Strings.nullToEmpty(Config.fe_membership_embedded_members_file).trim();
        if (!file.isEmpty()) {
            Path path = Paths.get(file);
            if (!Files.isReadable(path)) {
                throw new MembershipException("fe_membership_embedded_members_file " + path + " is not readable");
            }
            this.membersFile = path;
            this.lastAccepted = MembersFile.load(path);
        }
        LOG.info("embedded membership: seeds {}, seed dns '{}', members file {}", staticSeeds, seedDns, membersFile);
    }

    static List<HostPort> parseSeeds(String value) throws MembershipException {
        List<HostPort> seeds = new ArrayList<>();
        for (String item : Strings.nullToEmpty(value).split(",")) {
            String s = item.trim();
            if (s.isEmpty()) {
                continue;
            }
            HostPort hostPort;
            try {
                hostPort = HostPort.parse(s);
            } catch (IllegalArgumentException e) {
                throw new MembershipException("fe_seed_nodes: " + e.getMessage());
            }
            if (!seeds.contains(hostPort)) {
                seeds.add(hostPort);
            }
        }
        return seeds;
    }

    /**
     * Static seeds in configured order, then DNS seeds sorted by host so that every FE computes the same list.
     */
    @Override
    public List<HostPort> seeds() throws MembershipException {
        List<HostPort> seeds = new ArrayList<>(staticSeeds);
        if (seedDns.isEmpty()) {
            return seeds;
        }
        List<HostPort> resolved = new ArrayList<>();
        try {
            for (InetAddress address : InetAddress.getAllByName(seedDns)) {
                String host = ctx.useFqdn() ? address.getCanonicalHostName() : address.getHostAddress();
                HostPort hostPort = new HostPort(host, Config.edit_log_port);
                if (!seeds.contains(hostPort) && !resolved.contains(hostPort)) {
                    resolved.add(hostPort);
                }
            }
        } catch (UnknownHostException e) {
            if (seeds.isEmpty()) {
                throw new MembershipException("cannot resolve seed dns " + seedDns + ": " + e.getMessage(), e);
            }
            LOG.warn("cannot resolve seed dns {}, using static seeds only: {}", seedDns, e.getMessage());
            return seeds;
        }
        resolved.sort(Comparator.comparing(HostPort::host));
        seeds.addAll(resolved);
        return seeds;
    }

    @Override
    public Optional<String> existingClusterId() {
        return Optional.empty();
    }

    /**
     * The first seed is the candidate. With no seeds at all this FE is alone and may bootstrap.
     */
    @Override
    public boolean isBootstrapCandidate() throws MembershipException {
        List<HostPort> seeds = seeds();
        return seeds.isEmpty() || seeds.get(0).sameNode(ctx.self());
    }

    @Override
    public void recordClusterId(String clusterId) {
    }

    @Override
    public void announce(MemberInfo self) {
    }

    @Override
    public Optional<Set<FrontendSpec>> expectedFrontends() {
        return Optional.ofNullable(currentMembers()).map(MembersFile::getFrontends);
    }

    @Override
    public Optional<Set<ComputeNodeSpec>> expectedComputeNodes() {
        return Optional.ofNullable(currentMembers()).map(MembersFile::getComputeNodes);
    }

    /**
     * Re-reads the members file and accepts it only when its generation did not go backwards, so a stale
     * copy left on the leader's host cannot shrink the desired membership.
     */
    private MembersFile currentMembers() {
        if (membersFile == null) {
            return null;
        }
        MembersFile previous = lastAccepted;
        try {
            MembersFile parsed = MembersFile.load(membersFile);
            if (previous != null && parsed.getGeneration() < previous.getGeneration()) {
                LOG.warn("members file {} has generation {} older than the accepted {}, keeping the accepted one",
                        membersFile, parsed.getGeneration(), previous.getGeneration());
                return previous;
            }
            lastAccepted = parsed;
            return parsed;
        } catch (MembershipException e) {
            LOG.warn("members file {} is unreadable, keeping the last accepted one: {}", membersFile, e.getMessage());
            return previous;
        }
    }
}
