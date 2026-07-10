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

import com.starrocks.analysis.DescriptorTable;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.PaimonTable;
import com.starrocks.catalog.Type;
import com.starrocks.connector.paimon.PaimonDmlOperation;
import com.starrocks.connector.paimon.PaimonDmlSupport;
import com.starrocks.planner.DataSink;
import com.starrocks.planner.PaimonTableSink;
import com.starrocks.planner.PlanFragment;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.Optimizer;
import com.starrocks.sql.optimizer.OptimizerFactory;
import com.starrocks.sql.optimizer.base.ColumnRefFactory;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.transformer.LogicalPlan;
import com.starrocks.sql.optimizer.transformer.RelationTransformer;
import com.starrocks.sql.plan.ExecPlan;
import com.starrocks.sql.plan.PlanFragmentBuilder;
import com.starrocks.thrift.TResultSinkType;

import java.util.List;

/** Builds the common physical plan used by Paimon UPDATE, DELETE and MERGE. */
public final class PaimonDmlPlanner {
    private PaimonDmlPlanner() {
    }

    public static ExecPlan plan(QueryRelation query, PaimonTable table, PaimonDmlOperation operation,
                                ConnectContext session) {
        ColumnRefFactory columnRefFactory = new ColumnRefFactory();
        LogicalPlan logicalPlan = new RelationTransformer(columnRefFactory, session).transform(query);
        List<String> columnNames = query.getColumnOutputNames();

        Optimizer optimizer = OptimizerFactory.create(OptimizerFactory.initContext(session, columnRefFactory));
        OptExpression optimizedPlan = optimizer.optimize(logicalPlan.getRoot(), new PhysicalPropertySet(),
                new ColumnRefSet(logicalPlan.getOutputColumn()));
        ExecPlan execPlan = PlanFragmentBuilder.createPhysicalPlan(optimizedPlan, session, logicalPlan.getOutputColumn(),
                columnRefFactory, columnNames, TResultSinkType.MYSQL_PROTOCAL, false);

        DescriptorTable descriptorTable = execPlan.getDescTbl();
        TupleDescriptor tuple = descriptorTable.createTupleDescriptor();
        descriptorTable.addReferencedTable(table);
        for (Column column : table.getBaseSchema()) {
            addSlot(descriptorTable, tuple, column, column.getType(), column.isAllowNull());
        }
        SlotDescriptor rowKindSlot = addSlot(descriptorTable, tuple,
                new Column(PaimonDmlSupport.ROW_KIND_COLUMN, Type.TINYINT),
                Type.TINYINT, false);
        tuple.computeMemLayout();

        DataSink sink = PaimonTableSink.createDmlSink(table, tuple, operation);
        PlanFragment sinkFragment = execPlan.getFragments().get(0);
        sinkFragment.setSink(sink);
        sinkFragment.setPipelineDop(1);
        return execPlan;
    }

    private static SlotDescriptor addSlot(DescriptorTable descriptorTable, TupleDescriptor tuple, Column column, Type type,
                                          boolean nullable) {
        SlotDescriptor slot = descriptorTable.addSlotDescriptor(tuple);
        slot.setIsMaterialized(true);
        slot.setType(type);
        slot.setColumn(column);
        slot.setIsNullable(nullable);
        return slot;
    }
}
