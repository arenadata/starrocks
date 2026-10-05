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
 * Identity of a running FE as announced to the membership registry.
 */
public record MemberInfo(HostPort hostPort, FrontendNodeType role, String nodeName, String clusterId) {

    public MemberInfo {
        Objects.requireNonNull(hostPort);
        Objects.requireNonNull(role);
        Objects.requireNonNull(nodeName);
        Objects.requireNonNull(clusterId);
    }

    @Override
    public String toString() {
        return nodeName + "(" + hostPort + ", " + role + ", cluster " + clusterId + ")";
    }
}
