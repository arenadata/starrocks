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

import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.ComputeNodeSpec;
import com.starrocks.membership.FrontendSpec;
import com.starrocks.membership.HostPort;
import com.starrocks.membership.MembershipException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Desired membership file of the embedded provider:
 * <pre>
 * generation=7
 *
 * [frontends]
 * fe-1.example.org:9010 FOLLOWER
 * fe-3.example.org:9010 OBSERVER
 *
 * [compute_nodes]
 * cn-1.example.org:9050
 * cn-2.example.org:9050 default_warehouse
 * </pre>
 * The role defaults to FOLLOWER, the warehouse to the default one. '#' starts a comment.
 */
public final class MembersFile {
    private static final String GENERATION_KEY = "generation";
    private static final String FRONTENDS_SECTION = "[frontends]";
    private static final String COMPUTE_NODES_SECTION = "[compute_nodes]";

    private final long generation;
    private final Set<FrontendSpec> frontends;
    private final Set<ComputeNodeSpec> computeNodes;

    private MembersFile(long generation, Set<FrontendSpec> frontends, Set<ComputeNodeSpec> computeNodes) {
        this.generation = generation;
        this.frontends = Collections.unmodifiableSet(frontends);
        this.computeNodes = Collections.unmodifiableSet(computeNodes);
    }

    public static MembersFile load(Path path) throws MembershipException {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MembershipException("cannot read members file " + path + ": " + e.getMessage(), e);
        }
        try {
            return parse(lines);
        } catch (MembershipException e) {
            throw new MembershipException(path + ": " + e.getMessage(), e);
        }
    }

    public static MembersFile parse(List<String> lines) throws MembershipException {
        long generation = 0;
        boolean generationSeen = false;
        String section = null;
        Set<HostPort> seen = new LinkedHashSet<>();
        Set<FrontendSpec> frontends = new LinkedHashSet<>();
        Set<ComputeNodeSpec> computeNodes = new LinkedHashSet<>();

        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = stripComment(lines.get(i)).trim();
            if (line.isEmpty()) {
                continue;
            }
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.equals(FRONTENDS_SECTION) || lower.equals(COMPUTE_NODES_SECTION)) {
                section = lower;
                continue;
            }
            if (lower.startsWith("[")) {
                throw new MembershipException("line " + lineNo + ": unknown section " + line);
            }
            if (lower.startsWith(GENERATION_KEY + "=")) {
                if (generationSeen) {
                    throw new MembershipException("line " + lineNo + ": generation is set twice");
                }
                generation = parseGeneration(line.substring(GENERATION_KEY.length() + 1).trim(), lineNo);
                generationSeen = true;
                continue;
            }
            if (section == null) {
                throw new MembershipException("line " + lineNo + ": entry outside of a section: " + line);
            }

            String[] fields = line.split("\\s+");
            HostPort hostPort;
            try {
                hostPort = HostPort.parse(fields[0]);
            } catch (IllegalArgumentException e) {
                throw new MembershipException("line " + lineNo + ": " + e.getMessage());
            }
            if (!seen.add(hostPort)) {
                throw new MembershipException("line " + lineNo + ": duplicate node " + hostPort);
            }

            if (section.equals(FRONTENDS_SECTION)) {
                if (fields.length > 2) {
                    throw new MembershipException("line " + lineNo + ": expected 'host:port [FOLLOWER|OBSERVER]'");
                }
                frontends.add(new FrontendSpec(hostPort, parseRole(fields.length > 1 ? fields[1] : null, lineNo)));
            } else {
                if (fields.length > 3) {
                    throw new MembershipException("line " + lineNo + ": expected 'host:port [warehouse [cngroup]]'");
                }
                String warehouse = fields.length > 1 ? fields[1] : null;
                String cnGroup = fields.length > 2 ? fields[2] : null;
                computeNodes.add(new ComputeNodeSpec(hostPort, warehouse, cnGroup));
            }
        }
        return new MembersFile(generation, frontends, computeNodes);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static long parseGeneration(String value, int lineNo) throws MembershipException {
        try {
            long generation = Long.parseLong(value);
            if (generation < 0) {
                throw new NumberFormatException();
            }
            return generation;
        } catch (NumberFormatException e) {
            throw new MembershipException("line " + lineNo + ": generation must be a non-negative integer, got '"
                    + value + "'");
        }
    }

    private static FrontendNodeType parseRole(String value, int lineNo) throws MembershipException {
        try {
            return FrontendSpec.parseRole(value);
        } catch (IllegalArgumentException e) {
            throw new MembershipException("line " + lineNo + ": " + e.getMessage());
        }
    }

    public long getGeneration() {
        return generation;
    }

    public Set<FrontendSpec> getFrontends() {
        return frontends;
    }

    public Set<ComputeNodeSpec> getComputeNodes() {
        return computeNodes;
    }
}
