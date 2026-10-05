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

import com.starrocks.ha.FrontendNodeType;
import com.starrocks.membership.MembershipJoiner.Decision;
import com.starrocks.membership.MembershipJoiner.JoinOutcome;
import com.starrocks.membership.MembershipJoiner.LeaderClient;
import com.starrocks.membership.MembershipJoiner.LeaderInfo;
import com.starrocks.server.RunMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class MembershipJoinerTest {
    private static final HostPort SELF = new HostPort("10.0.0.3", 9010);
    private static final HostPort SEED1 = new HostPort("10.0.0.1", 9010);
    private static final HostPort SEED2 = new HostPort("10.0.0.2", 9010);
    private static final HostPort LEADER = SEED1;
    private static final int LEADER_HTTP_PORT = 8030;

    private static final MembershipContext CTX = new MembershipContext(SELF, FrontendNodeType.FOLLOWER, false);

    private static class FakeClient implements LeaderClient {
        final Map<HostPort, LeaderInfo> leaders = new HashMap<>();
        final Set<HostPort> unreachable = new HashSet<>();
        final List<HostPort> leaderCalls = new ArrayList<>();
        final List<HostPort> joinCalls = new ArrayList<>();
        JoinOutcome joinOutcome;
        boolean joinUnreachable = false;

        @Override
        public Optional<LeaderInfo> leader(HostPort seed) throws IOException {
            leaderCalls.add(seed);
            if (unreachable.contains(seed)) {
                throw new IOException("connection refused");
            }
            return Optional.ofNullable(leaders.get(seed));
        }

        @Override
        public JoinOutcome join(HostPort leaderHttp, HostPort self, FrontendNodeType role) throws IOException {
            joinCalls.add(leaderHttp);
            if (joinUnreachable) {
                throw new IOException("connection reset");
            }
            return joinOutcome;
        }
    }

    private static class CountingSleeper implements MembershipJoiner.Sleeper {
        int sleeps = 0;
        Runnable onSleep = () -> { };

        @Override
        public void sleep(long millis) {
            sleeps++;
            onSleep.run();
        }
    }

    private static LeaderInfo leaderInfo() {
        return new LeaderInfo(LEADER, LEADER_HTTP_PORT, "42", RunMode.name());
    }

    private static JoinOutcome joined(HostPort helper) {
        return new JoinOutcome(200, "", FrontendNodeType.FOLLOWER, "10.0.0.3_9010_1", helper);
    }

    private static MembershipJoiner joiner(FakeMembershipProvider provider, FakeClient client, CountingSleeper sleeper,
                                           boolean stateNew, List<HostPort> extraSeeds) {
        return new MembershipJoiner(provider, CTX, extraSeeds, client, sleeper, () -> stateNew);
    }

    @Test
    public void testJoinsThroughFirstAnsweringSeed() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SEED1, SEED2);
        FakeClient client = new FakeClient();
        client.unreachable.add(SEED1);
        client.leaders.put(SEED2, leaderInfo());
        client.joinOutcome = joined(LEADER);
        CountingSleeper sleeper = new CountingSleeper();

        Decision decision = joiner(provider, client, sleeper, false, List.of()).resolve();

        Assertions.assertEquals(Decision.ofJoin(LEADER), decision);
        Assertions.assertEquals(List.of(SEED1, SEED2), client.leaderCalls);
        Assertions.assertEquals(List.of(new HostPort(LEADER.host(), LEADER_HTTP_PORT)), client.joinCalls);
        Assertions.assertEquals(0, sleeper.sleeps);
    }

    @Test
    public void testWaitsUntilLeaderAppearsWhenStateIsExisting() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SEED1);
        provider.candidate = true;
        FakeClient client = new FakeClient();
        client.joinOutcome = joined(LEADER);
        CountingSleeper sleeper = new CountingSleeper();
        sleeper.onSleep = () -> client.leaders.put(SEED1, leaderInfo());

        Decision decision = joiner(provider, client, sleeper, false, List.of()).resolve();

        Assertions.assertEquals(Decision.ofJoin(LEADER), decision);
        Assertions.assertEquals(1, sleeper.sleeps);
    }

    @Test
    public void testBootstrapsWhenCandidateAndStateIsNew() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.candidate = true;
        CountingSleeper sleeper = new CountingSleeper();

        Decision decision = joiner(provider, new FakeClient(), sleeper, true, List.of()).resolve();

        Assertions.assertEquals(Decision.ofBootstrap(), decision);
        Assertions.assertEquals(0, sleeper.sleeps);
    }

    @Test
    public void testNonCandidateWaitsForBootstrap() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SEED1);
        provider.candidate = false;
        FakeClient client = new FakeClient();
        client.unreachable.add(SEED1);

        Assertions.assertNull(joiner(provider, client, new CountingSleeper(), true, List.of()).attempt(1));
    }

    @Test
    public void testRecordedClusterForbidsBootstrap() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.candidate = true;
        provider.recordedClusterId = Optional.of("42");

        Assertions.assertNull(joiner(provider, new FakeClient(), new CountingSleeper(), true, List.of()).attempt(1));
    }

    @Test
    public void testRejectedJoinIsFatal() {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SEED1);
        FakeClient client = new FakeClient();
        client.leaders.put(SEED1, leaderInfo());
        client.joinOutcome = JoinOutcome.failed(401, "token mismatch");

        MembershipException e = Assertions.assertThrows(MembershipException.class,
                () -> joiner(provider, client, new CountingSleeper(), false, List.of()).attempt(1));
        Assertions.assertTrue(e.getMessage().contains("401 token mismatch"), e.getMessage());
    }

    @Test
    public void testLeaderWithoutMembershipApiBecomesPlainHelper() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SEED1);
        FakeClient client = new FakeClient();
        client.leaders.put(SEED1, leaderInfo());
        client.joinOutcome = JoinOutcome.failed(404, "");

        Assertions.assertEquals(Decision.ofJoin(LEADER),
                joiner(provider, client, new CountingSleeper(), false, List.of()).attempt(1));
    }

    @Test
    public void testUnavailableLeaderIsRetried() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SEED1);
        FakeClient client = new FakeClient();
        client.leaders.put(SEED1, leaderInfo());
        client.joinOutcome = JoinOutcome.failed(503, "lock");
        MembershipJoiner joiner = joiner(provider, client, new CountingSleeper(), false, List.of());
        Assertions.assertNull(joiner.attempt(1));

        client.joinUnreachable = true;
        Assertions.assertNull(joiner.attempt(2));
    }

    @Test
    public void testRunModeMismatchIsFatal() {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SEED1);
        FakeClient client = new FakeClient();
        client.leaders.put(SEED1, new LeaderInfo(LEADER, LEADER_HTTP_PORT, "42", "some_other_mode"));

        Assertions.assertThrows(MembershipException.class,
                () -> joiner(provider, client, new CountingSleeper(), false, List.of()).attempt(1));
    }

    @Test
    public void testSelfIsSkippedAndExtraSeedsAreUsed() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seeds = List.of(SELF);
        FakeClient client = new FakeClient();
        client.leaders.put(SEED2, leaderInfo());
        client.joinOutcome = joined(LEADER);

        Decision decision = joiner(provider, client, new CountingSleeper(), false, List.of(SEED2, SELF)).attempt(1);

        Assertions.assertEquals(Decision.ofJoin(LEADER), decision);
        Assertions.assertEquals(List.of(SEED2), client.leaderCalls);
    }

    @Test
    public void testProviderSeedFailureFallsBackToExtraSeeds() throws Exception {
        FakeMembershipProvider provider = new FakeMembershipProvider();
        provider.seedsFail = true;
        FakeClient client = new FakeClient();
        client.leaders.put(SEED1, leaderInfo());
        client.joinOutcome = joined(LEADER);

        Assertions.assertEquals(Decision.ofJoin(LEADER),
                joiner(provider, client, new CountingSleeper(), false, List.of(SEED1)).attempt(1));
    }
}
