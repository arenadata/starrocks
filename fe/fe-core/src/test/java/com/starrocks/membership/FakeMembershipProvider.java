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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scriptable provider for tests: every answer is a public field.
 */
public class FakeMembershipProvider implements MembershipProvider {
    public List<HostPort> seeds = new ArrayList<>();
    public boolean seedsFail = false;
    public Optional<String> recordedClusterId = Optional.empty();
    public boolean candidate = false;
    public Optional<Set<FrontendSpec>> expectedFrontends = Optional.empty();
    public Optional<Set<ComputeNodeSpec>> expectedComputeNodes = Optional.empty();

    public final List<String> recordedClusterIds = new ArrayList<>();
    public final List<MemberInfo> announced = new ArrayList<>();

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public void start(MembershipContext ctx) {
    }

    @Override
    public List<HostPort> seeds() throws MembershipException {
        if (seedsFail) {
            throw new MembershipException("registry down");
        }
        return seeds;
    }

    @Override
    public Optional<String> existingClusterId() {
        return recordedClusterId;
    }

    @Override
    public boolean isBootstrapCandidate() {
        return candidate;
    }

    @Override
    public void recordClusterId(String clusterId) {
        recordedClusterIds.add(clusterId);
        recordedClusterId = Optional.of(clusterId);
    }

    @Override
    public void announce(MemberInfo self) {
        announced.add(self);
    }

    @Override
    public Optional<Set<FrontendSpec>> expectedFrontends() {
        return expectedFrontends;
    }

    @Override
    public Optional<Set<ComputeNodeSpec>> expectedComputeNodes() {
        return expectedComputeNodes;
    }
}
