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
import com.starrocks.server.WarehouseManager;

import java.util.Objects;

/**
 * Desired CN member: its heartbeat address and the warehouse / CN group it belongs to.
 * A missing warehouse means the default one, a missing CN group the empty string.
 */
public record ComputeNodeSpec(HostPort hostPort, String warehouse, String cnGroup) {

    public ComputeNodeSpec {
        Objects.requireNonNull(hostPort);
        warehouse = Strings.isNullOrEmpty(warehouse) ? WarehouseManager.DEFAULT_WAREHOUSE_NAME : warehouse;
        cnGroup = Strings.nullToEmpty(cnGroup);
    }

    public ComputeNodeSpec(HostPort hostPort) {
        this(hostPort, null, null);
    }

    @Override
    public String toString() {
        return hostPort + " " + warehouse + (cnGroup.isEmpty() ? "" : " " + cnGroup);
    }
}
