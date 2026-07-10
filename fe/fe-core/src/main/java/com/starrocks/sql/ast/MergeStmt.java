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

package com.starrocks.sql.ast;

import com.starrocks.analysis.Expr;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.Table;
import com.starrocks.sql.parser.NodePosition;

import java.util.List;

/** ANSI MERGE statement, currently planned only for primary-key Paimon tables. */
public class MergeStmt extends DmlStmt {
    public enum Action {
        INSERT,
        UPDATE,
        DELETE
    }

    public static class WhenClause {
        private final boolean matched;
        private final Expr condition;
        private final Action action;
        private final List<ColumnAssignment> assignments;
        private final List<String> insertColumns;
        private final List<Expr> insertValues;

        public WhenClause(boolean matched, Expr condition, Action action, List<ColumnAssignment> assignments,
                          List<String> insertColumns, List<Expr> insertValues) {
            this.matched = matched;
            this.condition = condition;
            this.action = action;
            this.assignments = assignments;
            this.insertColumns = insertColumns;
            this.insertValues = insertValues;
        }

        public boolean isMatched() {
            return matched;
        }

        public Expr getCondition() {
            return condition;
        }

        public Action getAction() {
            return action;
        }

        public List<ColumnAssignment> getAssignments() {
            return assignments;
        }

        public List<String> getInsertColumns() {
            return insertColumns;
        }

        public List<Expr> getInsertValues() {
            return insertValues;
        }
    }

    private final TableName tableName;
    private final Relation source;
    private final Expr onPredicate;
    private final List<WhenClause> whenClauses;
    private Table table;
    private QueryStatement queryStatement;

    public MergeStmt(TableName tableName, Relation source, Expr onPredicate, List<WhenClause> whenClauses,
                     NodePosition pos) {
        super(pos);
        this.tableName = tableName;
        this.source = source;
        this.onPredicate = onPredicate;
        this.whenClauses = whenClauses;
    }

    @Override
    public TableName getTableName() {
        return tableName;
    }

    public Relation getSource() {
        return source;
    }

    public Expr getOnPredicate() {
        return onPredicate;
    }

    public List<WhenClause> getWhenClauses() {
        return whenClauses;
    }

    public Table getTable() {
        return table;
    }

    public void setTable(Table table) {
        this.table = table;
    }

    public QueryStatement getQueryStatement() {
        return queryStatement;
    }

    public void setQueryStatement(QueryStatement queryStatement) {
        this.queryStatement = queryStatement;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitMergeStatement(this, context);
    }
}
