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

package com.starrocks.sql.analyzer;

import com.starrocks.analysis.CaseExpr;
import com.starrocks.analysis.CaseWhenClause;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.BinaryType;
import com.starrocks.analysis.CompoundPredicate;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.IsNullPredicate;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.TableName;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.PaimonTable;
import com.starrocks.catalog.Table;
import com.starrocks.connector.paimon.PaimonDmlOperation;
import com.starrocks.connector.paimon.PaimonDmlSupport;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.ColumnAssignment;
import com.starrocks.sql.ast.JoinRelation;
import com.starrocks.sql.ast.MergeStmt;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.Relation;
import com.starrocks.sql.ast.SetQualifier;
import com.starrocks.sql.ast.SelectList;
import com.starrocks.sql.ast.SelectListItem;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.TableRelation;
import com.starrocks.sql.ast.UnionRelation;
import com.starrocks.sql.common.MetaUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Lowers a supported ANSI MERGE into a Paimon row-kind query. */
public final class MergeAnalyzer {
    private MergeAnalyzer() {
    }

    public static void analyze(MergeStmt statement, ConnectContext session) {
        TableName tableName = statement.getTableName();
        Table table = MetaUtils.getSessionAwareTable(session, null, tableName);
        PaimonDmlSupport.requireRowLevelDml(table, session, "MERGE");
        if (!(table instanceof PaimonTable)) {
            throw new SemanticException("MERGE is supported only for Paimon primary-key tables");
        }
        if (!(statement.getSource() instanceof TableRelation)) {
            throw new SemanticException("MERGE source must be a primary-key table relation to avoid multiple matches");
        }

        PaimonTable target = (PaimonTable) table;
        TableRelation source = (TableRelation) statement.getSource();
        List<Expr> actionPredicates = new ArrayList<>();
        List<CaseWhenClause> rowKinds = new ArrayList<>();
        List<Expr> rawPredicates = new ArrayList<>();
        List<Expr> updatePredicates = new ArrayList<>();

        String primaryKey = target.getPrimaryKeyColumnNames().get(0);
        Expr targetPresent = new IsNullPredicate(new SlotRef(tableName, primaryKey), true);
        Expr targetMissing = new IsNullPredicate(new SlotRef(tableName, primaryKey), false);
        for (MergeStmt.WhenClause clause : statement.getWhenClauses()) {
            validateClause(clause, target);
            Expr predicate = clause.isMatched() ? targetPresent.clone() : targetMissing.clone();
            if (clause.getCondition() != null) {
                predicate = new CompoundPredicate(CompoundPredicate.Operator.AND, predicate, clause.getCondition());
            }
            Expr effectivePredicate = predicate;
            if (!rawPredicates.isEmpty()) {
                effectivePredicate = new CompoundPredicate(CompoundPredicate.Operator.AND, predicate,
                        new CompoundPredicate(CompoundPredicate.Operator.NOT, or(rawPredicates), null));
            }
            rawPredicates.add(predicate);
            actionPredicates.add(effectivePredicate);
            if (clause.getAction() == MergeStmt.Action.UPDATE) {
                updatePredicates.add(effectivePredicate);
            }
            rowKinds.add(new CaseWhenClause(effectivePredicate.clone(),
                    new com.starrocks.analysis.IntLiteral(rowKind(clause), com.starrocks.catalog.Type.TINYINT)));
        }

        Relation targetRelation = new TableRelation(tableName);
        Relation relation = new JoinRelation(com.starrocks.analysis.JoinOperator.FULL_OUTER_JOIN,
                targetRelation, source, statement.getOnPredicate(), false);
        SelectList selectList = new SelectList();
        for (int columnIndex = 0; columnIndex < target.getBaseSchema().size(); columnIndex++) {
            Column column = target.getBaseSchema().get(columnIndex);
            List<CaseWhenClause> values = new ArrayList<>();
            for (int i = 0; i < statement.getWhenClauses().size(); i++) {
                MergeStmt.WhenClause clause = statement.getWhenClauses().get(i);
                Expr value = actionValue(clause, column, tableName, columnIndex);
                if (value != null) {
                    values.add(new CaseWhenClause(actionPredicates.get(i).clone(), value));
                }
            }
            Expr defaultValue = new SlotRef(tableName, column.getName());
            selectList.addItem(new SelectListItem(new CaseExpr(null, values, defaultValue), column.getName()));
        }
        selectList.addItem(new SelectListItem(new CaseExpr(null, rowKinds, null), PaimonDmlSupport.ROW_KIND_COLUMN));

        QueryRelation after = new SelectRelation(selectList, relation, or(actionPredicates), null, null);
        QueryRelation lowered = after;
        if (!updatePredicates.isEmpty()) {
            lowered = new UnionRelation(List.of(buildUpdateBefore(target, tableName, source, statement.getOnPredicate(),
                    updatePredicates), after), SetQualifier.ALL);
        }
        QueryStatement query = new QueryStatement(lowered);
        new QueryAnalyzer(session).analyze(query);
        validateUniqueMergeMatch(target, source, tableName, statement.getOnPredicate());
        statement.setTable(target);
        statement.setQueryStatement(query);
    }

    private static void validateClause(MergeStmt.WhenClause clause, PaimonTable table) {
        if (clause.isMatched() && clause.getAction() == MergeStmt.Action.INSERT) {
            throw new SemanticException("INSERT is allowed only in WHEN NOT MATCHED");
        }
        if (!clause.isMatched() && clause.getAction() != MergeStmt.Action.INSERT) {
            throw new SemanticException("UPDATE and DELETE are allowed only in WHEN MATCHED");
        }
        if (clause.getAction() == MergeStmt.Action.UPDATE) {
            for (ColumnAssignment assignment : clause.getAssignments()) {
                Column column = table.getColumn(assignment.getColumn());
                if (column == null) {
                    throw new SemanticException("Unknown column '%s' in MERGE", assignment.getColumn());
                }
                if (column.isKey()) {
                    throw new SemanticException("primary key column cannot be updated: " + column.getName());
                }
            }
        }
        if (clause.getAction() == MergeStmt.Action.INSERT) {
            List<String> columns = clause.getInsertColumns();
            int expected = columns == null ? table.getBaseSchema().size() : columns.size();
            if (clause.getInsertValues().size() != expected) {
                throw new SemanticException("MERGE INSERT column count does not match value count");
            }
            if (columns != null && columns.size() != table.getBaseSchema().size()) {
                throw new SemanticException("MERGE INSERT must provide every Paimon table column");
            }
        }
    }

    private static SelectRelation buildUpdateBefore(PaimonTable target, TableName tableName, TableRelation source,
                                                    Expr onPredicate, List<Expr> updatePredicates) {
        TableRelation sourceCopy = new TableRelation(source.getName());
        if (source.getAlias() != null) {
            sourceCopy.setAlias(source.getAlias());
        }
        Relation relation = new JoinRelation(com.starrocks.analysis.JoinOperator.FULL_OUTER_JOIN,
                new TableRelation(tableName), sourceCopy, onPredicate.clone(), false);
        SelectList selectList = new SelectList();
        for (Column column : target.getBaseSchema()) {
            selectList.addItem(new SelectListItem(new SlotRef(tableName, column.getName()), column.getName()));
        }
        selectList.addItem(new SelectListItem(
                new com.starrocks.analysis.IntLiteral(PaimonDmlOperation.UPDATE_BEFORE.getRowKind(),
                        com.starrocks.catalog.Type.TINYINT),
                PaimonDmlSupport.ROW_KIND_COLUMN));
        return new SelectRelation(selectList, relation, or(updatePredicates), null, null);
    }

    private static void validateUniqueMergeMatch(PaimonTable target, TableRelation source, TableName targetName,
                                                 Expr onPredicate) {
        List<String> sourceKeys;
        if (source.getTable() instanceof PaimonTable) {
            sourceKeys = ((PaimonTable) source.getTable()).getPrimaryKeyColumnNames();
        } else {
            sourceKeys = source.getTable().getKeyColumns().stream().map(Column::getName).collect(Collectors.toList());
        }
        if (sourceKeys.isEmpty()) {
            throw new SemanticException("MERGE source must have a primary key to avoid multiple matches");
        }

        Set<String> matchedSourceKeys = new HashSet<>();
        collectKeyEqualities(onPredicate, source, targetName, target.getPrimaryKeyColumnNames(), matchedSourceKeys);
        if (!matchedSourceKeys.containsAll(sourceKeys)) {
            throw new SemanticException("MERGE ON must equate every source primary-key column to a target primary key");
        }
    }

    private static void collectKeyEqualities(Expr expr, TableRelation source, TableName targetName,
                                             List<String> targetKeys, Set<String> matchedSourceKeys) {
        if (expr instanceof BinaryPredicate && ((BinaryPredicate) expr).getOp() == BinaryType.EQ
                && expr.getChild(0) instanceof SlotRef && expr.getChild(1) instanceof SlotRef) {
            SlotRef left = (SlotRef) expr.getChild(0);
            SlotRef right = (SlotRef) expr.getChild(1);
            collectKeyEquality(left, right, source, targetName, targetKeys, matchedSourceKeys);
            collectKeyEquality(right, left, source, targetName, targetKeys, matchedSourceKeys);
        }
        for (Expr child : expr.getChildren()) {
            collectKeyEqualities(child, source, targetName, targetKeys, matchedSourceKeys);
        }
    }

    private static void collectKeyEquality(SlotRef sourceSlot, SlotRef targetSlot, TableRelation source,
                                           TableName targetName, List<String> targetKeys,
                                           Set<String> matchedSourceKeys) {
        TableName sourceName = source.getAlias() == null ? source.getName() : source.getAlias();
        if (sourceSlot.getTblNameWithoutAnalyzed() != null && targetSlot.getTblNameWithoutAnalyzed() != null
                && sourceSlot.getTblNameWithoutAnalyzed().equals(sourceName)
                && targetSlot.getTblNameWithoutAnalyzed().equals(targetName)
                && targetKeys.stream().anyMatch(key -> key.equalsIgnoreCase(targetSlot.getColumnName()))) {
            matchedSourceKeys.add(sourceSlot.getColumnName());
        }
    }

    private static Expr actionValue(MergeStmt.WhenClause clause, Column column, TableName tableName, int columnIndex) {
        if (clause.getAction() == MergeStmt.Action.DELETE) {
            return new SlotRef(tableName, column.getName());
        }
        if (clause.getAction() == MergeStmt.Action.UPDATE) {
            for (ColumnAssignment assignment : clause.getAssignments()) {
                if (assignment.getColumn().equalsIgnoreCase(column.getName())) {
                    return assignment.getExpr();
                }
            }
            return new SlotRef(tableName, column.getName());
        }
        List<String> columns = clause.getInsertColumns();
        int index = columns == null ? -1 : findColumn(columns, column.getName());
        if (columns == null) {
            return clause.getInsertValues().get(columnIndex);
        }
        return index < 0 ? new SlotRef(tableName, column.getName()) : clause.getInsertValues().get(index);
    }

    private static int findColumn(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static int rowKind(MergeStmt.WhenClause clause) {
        switch (clause.getAction()) {
            case INSERT:
                return PaimonDmlOperation.INSERT.getRowKind();
            case UPDATE:
                return PaimonDmlOperation.UPDATE_AFTER.getRowKind();
            case DELETE:
                return PaimonDmlOperation.DELETE.getRowKind();
            default:
                throw new IllegalArgumentException("Unknown merge action");
        }
    }

    private static Expr or(List<Expr> predicates) {
        if (predicates.isEmpty()) {
            throw new SemanticException("MERGE must contain at least one WHEN clause");
        }
        Expr result = predicates.get(0);
        for (int i = 1; i < predicates.size(); i++) {
            result = new CompoundPredicate(CompoundPredicate.Operator.OR, result, predicates.get(i));
        }
        return result;
    }
}
