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

import com.google.common.base.Strings;
import com.starrocks.ha.FrontendNodeType;

import java.util.Locale;
import java.util.Objects;

/**
 * Desired FE member: its edit log address and the role it is expected to have.
 */
public record FrontendSpec(HostPort hostPort, FrontendNodeType role) {

    public FrontendSpec {
        Objects.requireNonNull(hostPort);
        if (role != FrontendNodeType.FOLLOWER && role != FrontendNodeType.OBSERVER) {
            throw new IllegalArgumentException("frontend role must be FOLLOWER or OBSERVER, got " + role);
        }
    }

    /**
     * Parses FOLLOWER or OBSERVER, case-insensitively. Null or blank means FOLLOWER.
     */
    public static FrontendNodeType parseRole(String value) {
        String role = Strings.nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
        return switch (role) {
            case "", "FOLLOWER" -> FrontendNodeType.FOLLOWER;
            case "OBSERVER" -> FrontendNodeType.OBSERVER;
            default -> throw new IllegalArgumentException("role must be FOLLOWER or OBSERVER, got '" + value + "'");
        };
    }

    @Override
    public String toString() {
        return hostPort + " " + role;
    }
}
