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

package com.starrocks.sql;

import com.starrocks.catalog.PaimonTable;
import com.starrocks.connector.paimon.PaimonDmlOperation;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.MergeStmt;
import com.starrocks.sql.plan.ExecPlan;

/** Plans the row-kind projection produced by {@link com.starrocks.sql.analyzer.MergeAnalyzer}. */
public class MergePlanner {
    public ExecPlan plan(MergeStmt statement, ConnectContext session) {
        if (!(statement.getTable() instanceof PaimonTable)) {
            throw new SemanticException("MERGE is supported only for Paimon primary-key tables");
        }
        return PaimonDmlPlanner.plan(statement.getQueryStatement().getQueryRelation(),
                (PaimonTable) statement.getTable(), PaimonDmlOperation.MERGE, session);
    }
}
