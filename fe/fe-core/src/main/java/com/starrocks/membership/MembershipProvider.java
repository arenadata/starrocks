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

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Source of cluster membership information outside the journal: where a fresh FE finds the other FEs,
 * whether a cluster already exists, and which FE / CN members are desired.
 * <p>
 * The journal stays the source of truth for the current membership; a provider only answers questions.
 * Implementations are discovered through {@link java.util.ServiceLoader} by {@link #name()}.
 */
public interface MembershipProvider extends AutoCloseable {

    /** Value of fe_membership_provider that selects this implementation. */
    String name();

    void start(MembershipContext ctx) throws MembershipException;

    /**
     * FE candidates (host:edit_log_port) to ask for the leader and to join through.
     * An empty list means there is nobody to ask.
     */
    List<HostPort> seeds() throws MembershipException;

    /**
     * Cluster id recorded in the registry. Empty when no cluster was recorded yet or the provider keeps no record.
     * A non-empty value forbids bootstrapping a new cluster.
     */
    Optional<String> existingClusterId() throws MembershipException;

    /**
     * Whether the local FE is the one allowed to bootstrap a new cluster when none exists.
     * Must be deterministic across the seed set: exactly one FE answers true.
     */
    boolean isBootstrapCandidate() throws MembershipException;

    /** Called by the leader once a new cluster was bootstrapped. No-op when the provider keeps no record. */
    void recordClusterId(String clusterId) throws MembershipException;

    /** Called once the local FE is ready. Providers with a presence registry publish the member here. */
    void announce(MemberInfo self) throws MembershipException;

    /** Desired FE members. Empty means unknown: the reconciler never drops FEs. */
    Optional<Set<FrontendSpec>> expectedFrontends() throws MembershipException;

    /** Desired CN members. Empty means unknown: the reconciler never adds or drops CNs. */
    Optional<Set<ComputeNodeSpec>> expectedComputeNodes() throws MembershipException;

    /** Registers a callback fired when the desired membership may have changed. Optional. */
    default void addChangeListener(Runnable onChange) {
    }

    /**
     * True when the provider carries its own proof of membership, e.g. a registration in a store
     * only cluster members can write to. Such a provider needs no shared auth_token.
     */
    default boolean requiresToken() {
        return true;
    }

    /**
     * True when the provider can prove that this exact node belongs to the cluster. The join
     * request of a vouched node is accepted without the shared token.
     */
    default boolean vouches(HostPort node) throws MembershipException {
        return false;
    }

    @Override
    default void close() {
    }
}
