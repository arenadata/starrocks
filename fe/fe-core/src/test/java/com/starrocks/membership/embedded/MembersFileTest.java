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
import com.starrocks.server.WarehouseManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MembersFileTest {

    @Test
    public void testParseFullFile() throws MembershipException {
        MembersFile file = MembersFile.parse(List.of(
                "# desired members",
                "generation=7",
                "",
                "[frontends]",
                "fe-1.example.org:9010 FOLLOWER",
                "fe-2.example.org:9010   # default role",
                "fe-3.example.org:9010 observer",
                "",
                "[compute_nodes]",
                "cn-1.example.org:9050",
                "cn-2.example.org:9050 wh1",
                "cn-3.example.org:9050 wh1 group_a"));

        Assertions.assertEquals(7, file.getGeneration());
        Assertions.assertEquals(List.of(
                new FrontendSpec(new HostPort("fe-1.example.org", 9010), FrontendNodeType.FOLLOWER),
                new FrontendSpec(new HostPort("fe-2.example.org", 9010), FrontendNodeType.FOLLOWER),
                new FrontendSpec(new HostPort("fe-3.example.org", 9010), FrontendNodeType.OBSERVER)),
                List.copyOf(file.getFrontends()));
        Assertions.assertEquals(List.of(
                new ComputeNodeSpec(new HostPort("cn-1.example.org", 9050),
                        WarehouseManager.DEFAULT_WAREHOUSE_NAME, ""),
                new ComputeNodeSpec(new HostPort("cn-2.example.org", 9050), "wh1", ""),
                new ComputeNodeSpec(new HostPort("cn-3.example.org", 9050), "wh1", "group_a")),
                List.copyOf(file.getComputeNodes()));
    }

    @Test
    public void testEmptyFile() throws MembershipException {
        MembersFile file = MembersFile.parse(List.of());
        Assertions.assertEquals(0, file.getGeneration());
        Assertions.assertTrue(file.getFrontends().isEmpty());
        Assertions.assertTrue(file.getComputeNodes().isEmpty());
    }

    @Test
    public void testSectionsMayBeEmptyOrMissing() throws MembershipException {
        MembersFile file = MembersFile.parse(List.of("generation=1", "[compute_nodes]", "cn-1:9050"));
        Assertions.assertTrue(file.getFrontends().isEmpty());
        Assertions.assertEquals(1, file.getComputeNodes().size());
    }

    @Test
    public void testErrors() {
        assertError(List.of("fe-1:9010"), "outside of a section");
        assertError(List.of("[frontends]", "fe-1"), "expected host:port");
        assertError(List.of("[frontends]", "fe-1:9010 LEADER"), "role must be FOLLOWER or OBSERVER");
        assertError(List.of("[frontends]", "fe-1:9010 FOLLOWER extra"), "expected 'host:port [FOLLOWER|OBSERVER]'");
        assertError(List.of("[compute_nodes]", "cn-1:9050 wh g extra"), "expected 'host:port [warehouse [cngroup]]'");
        assertError(List.of("[frontends]", "fe-1:9010", "fe-1:9010"), "duplicate node");
        assertError(List.of("[frontends]", "fe-1:9010", "[compute_nodes]", "fe-1:9010"), "duplicate node");
        assertError(List.of("generation=x"), "generation must be a non-negative integer");
        assertError(List.of("generation=-1"), "generation must be a non-negative integer");
        assertError(List.of("generation=1", "generation=2"), "generation is set twice");
        assertError(List.of("[backends]"), "unknown section");
    }

    private static void assertError(List<String> lines, String expectedMessage) {
        MembershipException e = Assertions.assertThrows(MembershipException.class, () -> MembersFile.parse(lines));
        Assertions.assertTrue(e.getMessage().contains(expectedMessage),
                "expected '" + expectedMessage + "' in: " + e.getMessage());
    }
}
