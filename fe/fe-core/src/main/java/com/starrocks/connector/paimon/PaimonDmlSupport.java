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

package com.starrocks.connector.paimon;

import com.starrocks.analysis.IntLiteral;
import com.starrocks.catalog.PaimonTable;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectListItem;

/**
 * Shared FE contract for Paimon row-level DML.
 *
 * <p>The final select item is intentionally not a Paimon table column.  It is a
 * hidden operation column consumed by the Paimon sink and passed through to the
 * paimon-cpp RecordBatchBuilder.
 */
public final class PaimonDmlSupport {
    public static final String ROW_KIND_COLUMN = "__paimon_row_kind";

    private PaimonDmlSupport() {
    }

    public static boolean isPaimonPrimaryKeyTable(Table table) {
        return table instanceof PaimonTable
                && !((PaimonTable) table).getPrimaryKeyColumnNames().isEmpty();
    }

    public static void requireRowLevelDml(Table table, ConnectContext session, String operation) {
        if (!(table instanceof PaimonTable)) {
            return;
        }
        if (!isPaimonPrimaryKeyTable(table)) {
            throw new SemanticException("Paimon %s requires a primary-key table", operation);
        }
        if (session.getTxnId() != 0) {
            throw new SemanticException("Paimon %s does not support explicit multi-statement transactions", operation);
        }
    }

    public static void appendRowKind(SelectList selectList, PaimonDmlOperation operation) {
        selectList.addItem(new SelectListItem(
                new IntLiteral(operation.getRowKind(), Type.TINYINT), ROW_KIND_COLUMN));
    }
}
