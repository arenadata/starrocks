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

/**
 * The row kind carried by the hidden Paimon DML column.
 *
 * <p>The row-kind values deliberately match Paimon's {@code RowKind} values so that
 * the native RecordBatchBuilder can consume the column without FE-side translation.
 * {@link #MERGE} is sink metadata; actual merge rows carry one of the four row kinds.
 */
public enum PaimonDmlOperation {
    INSERT(0),
    UPDATE_BEFORE(1),
    UPDATE_AFTER(2),
    DELETE(3),
    MERGE(-1);

    private final int rowKind;

    PaimonDmlOperation(int rowKind) {
        this.rowKind = rowKind;
    }

    public int getRowKind() {
        return rowKind;
    }
}
