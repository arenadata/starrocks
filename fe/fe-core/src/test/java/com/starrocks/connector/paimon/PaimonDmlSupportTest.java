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
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectListItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PaimonDmlSupportTest {
    @Test
    public void testRowKindsMatchPaimonWireContract() {
        assertEquals(0, PaimonDmlOperation.INSERT.getRowKind());
        assertEquals(1, PaimonDmlOperation.UPDATE_BEFORE.getRowKind());
        assertEquals(2, PaimonDmlOperation.UPDATE_AFTER.getRowKind());
        assertEquals(3, PaimonDmlOperation.DELETE.getRowKind());
        assertEquals(-1, PaimonDmlOperation.MERGE.getRowKind());
    }

    @Test
    public void testAppendRowKindCreatesTypedHiddenColumn() {
        SelectList selectList = new SelectList();

        PaimonDmlSupport.appendRowKind(selectList, PaimonDmlOperation.DELETE);

        assertEquals(1, selectList.getItems().size());
        SelectListItem item = selectList.getItems().get(0);
        assertEquals(PaimonDmlSupport.ROW_KIND_COLUMN, item.getAlias());
        assertEquals(3, ((IntLiteral) item.getExpr()).getLongValue());
    }
}
