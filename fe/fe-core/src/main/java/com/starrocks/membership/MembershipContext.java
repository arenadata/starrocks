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

import java.util.Objects;

/**
 * What a provider knows about the local FE when it starts.
 *
 * @param self        local FE as host:edit_log_port, host in the form chosen by FrontendOptions
 * @param desiredRole role this FE wants when it joins: FOLLOWER or OBSERVER
 * @param useFqdn     true when the local FE identifies itself by FQDN rather than IP
 */
public record MembershipContext(HostPort self, FrontendNodeType desiredRole, boolean useFqdn) {

    public MembershipContext {
        Objects.requireNonNull(self);
        Objects.requireNonNull(desiredRole);
    }
}
