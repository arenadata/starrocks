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

/**
 * Wire-level names of the membership HTTP API shared by the server action and the joiner client.
 */
public final class MembershipApi {
    public static final String LEADER_PATH = "/api/v2/membership/leader";
    public static final String JOIN_PATH = "/api/v2/membership/join";

    public static final String TOKEN_HEADER = "token";

    public static final String TYPE_FRONTEND = "FRONTEND";
    public static final String TYPE_COMPUTE_NODE = "COMPUTE_NODE";

    private MembershipApi() {
    }
}
