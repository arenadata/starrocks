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

import com.starrocks.common.Config;
import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.ComputeNodeSpec;
import com.starrocks.membership.FrontendSpec;
import com.starrocks.membership.HostPort;
import com.starrocks.membership.MembershipContext;
import com.starrocks.membership.MembershipException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class EmbeddedMembershipProviderTest {

    @TempDir
    Path tempDir;

    @AfterEach
    public void resetConfig() {
        Config.fe_seed_nodes = "";
        Config.fe_membership_embedded_seed_dns = "";
        Config.fe_membership_embedded_members_file = "";
    }

    private static MembershipContext ctx(String host) {
        return new MembershipContext(new HostPort(host, 9010), FrontendNodeType.FOLLOWER, false);
    }

    @Test
    public void testStaticSeedsAndBootstrapCandidate() throws MembershipException {
        Config.fe_seed_nodes = " 10.0.0.1:9010, 10.0.0.2:9010 ,10.0.0.1:9010,";

        EmbeddedMembershipProvider first = new EmbeddedMembershipProvider();
        first.start(ctx("10.0.0.1"));
        Assertions.assertEquals(
                List.of(new HostPort("10.0.0.1", 9010), new HostPort("10.0.0.2", 9010)), first.seeds());
        Assertions.assertTrue(first.isBootstrapCandidate());

        EmbeddedMembershipProvider second = new EmbeddedMembershipProvider();
        second.start(ctx("10.0.0.2"));
        Assertions.assertFalse(second.isBootstrapCandidate());

        Assertions.assertEquals(Optional.empty(), first.existingClusterId());
        Assertions.assertEquals(Optional.empty(), first.expectedFrontends());
        Assertions.assertEquals(Optional.empty(), first.expectedComputeNodes());
    }

    @Test
    public void testNoSeedsMeansAlone() throws MembershipException {
        EmbeddedMembershipProvider provider = new EmbeddedMembershipProvider();
        provider.start(ctx("10.0.0.1"));
        Assertions.assertTrue(provider.seeds().isEmpty());
        Assertions.assertTrue(provider.isBootstrapCandidate());
    }

    @Test
    public void testBadSeedRejectedAtStart() {
        Config.fe_seed_nodes = "10.0.0.1:9010,oops";
        EmbeddedMembershipProvider provider = new EmbeddedMembershipProvider();
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> provider.start(ctx("10.0.0.1")));
        Assertions.assertTrue(e.getMessage().startsWith("fe_seed_nodes:"), e.getMessage());
    }

    @Test
    public void testDnsSeedsAppendedAfterStaticOnes() throws MembershipException {
        Config.fe_seed_nodes = "10.0.0.9:9010";
        Config.fe_membership_embedded_seed_dns = "localhost";
        int editLogPort = Config.edit_log_port;

        EmbeddedMembershipProvider provider = new EmbeddedMembershipProvider();
        provider.start(ctx("10.0.0.9"));
        List<HostPort> seeds = provider.seeds();
        Assertions.assertEquals(new HostPort("10.0.0.9", 9010), seeds.get(0));
        Assertions.assertTrue(seeds.size() > 1, seeds.toString());
        Assertions.assertTrue(seeds.stream().skip(1).allMatch(hp -> hp.port() == editLogPort), seeds.toString());
    }

    @Test
    public void testUnresolvableDnsFallsBackToStaticSeeds() throws MembershipException {
        Config.fe_seed_nodes = "10.0.0.9:9010";
        Config.fe_membership_embedded_seed_dns = "no-such-host.invalid";
        EmbeddedMembershipProvider provider = new EmbeddedMembershipProvider();
        provider.start(ctx("10.0.0.9"));
        Assertions.assertEquals(List.of(new HostPort("10.0.0.9", 9010)), provider.seeds());

        Config.fe_seed_nodes = "";
        EmbeddedMembershipProvider dnsOnly = new EmbeddedMembershipProvider();
        dnsOnly.start(ctx("10.0.0.9"));
        Assertions.assertThrows(MembershipException.class, dnsOnly::seeds);
    }

    @Test
    public void testMembersFileGenerationGuard() throws MembershipException, IOException {
        Path file = tempDir.resolve("members");
        write(file, "generation=2", "[frontends]", "fe-1:9010", "fe-2:9010 OBSERVER", "[compute_nodes]", "cn-1:9050");
        Config.fe_membership_embedded_members_file = file.toString();

        EmbeddedMembershipProvider provider = new EmbeddedMembershipProvider();
        provider.start(ctx("fe-1"));
        Set<FrontendSpec> frontends = provider.expectedFrontends().orElseThrow();
        Assertions.assertEquals(Set.of(
                new FrontendSpec(new HostPort("fe-1", 9010), FrontendNodeType.FOLLOWER),
                new FrontendSpec(new HostPort("fe-2", 9010), FrontendNodeType.OBSERVER)), frontends);
        Assertions.assertEquals(Set.of(new ComputeNodeSpec(new HostPort("cn-1", 9050))),
                provider.expectedComputeNodes().orElseThrow());

        // an older generation is ignored
        write(file, "generation=1", "[frontends]", "fe-1:9010");
        Assertions.assertEquals(frontends, provider.expectedFrontends().orElseThrow());

        // a newer generation replaces the accepted content
        write(file, "generation=3", "[frontends]", "fe-1:9010");
        Assertions.assertEquals(Set.of(new FrontendSpec(new HostPort("fe-1", 9010), FrontendNodeType.FOLLOWER)),
                provider.expectedFrontends().orElseThrow());
        Assertions.assertEquals(Set.of(), provider.expectedComputeNodes().orElseThrow());

        // an unparsable file keeps the accepted content
        write(file, "[frontends]", "garbage");
        Assertions.assertEquals(Set.of(new FrontendSpec(new HostPort("fe-1", 9010), FrontendNodeType.FOLLOWER)),
                provider.expectedFrontends().orElseThrow());
    }

    @Test
    public void testMissingMembersFileRejectedAtStart() {
        Config.fe_membership_embedded_members_file = tempDir.resolve("missing").toString();
        EmbeddedMembershipProvider provider = new EmbeddedMembershipProvider();
        Assertions.assertThrows(MembershipException.class, () -> provider.start(ctx("fe-1")));
    }

    private static void write(Path file, String... lines) throws IOException {
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
    }
}
