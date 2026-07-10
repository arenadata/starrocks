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

import com.starrocks.analysis.ColumnPosition;
import com.starrocks.catalog.Column;
import com.starrocks.common.DdlException;
import com.starrocks.connector.ConnectorAlterTableExecutor;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.AddColumnClause;
import com.starrocks.sql.ast.AddColumnsClause;
import com.starrocks.sql.ast.AlterTableCommentClause;
import com.starrocks.sql.ast.AlterTableStmt;
import com.starrocks.sql.ast.ColumnDef;
import com.starrocks.sql.ast.ColumnRenameClause;
import com.starrocks.sql.ast.DropColumnClause;
import com.starrocks.sql.ast.ModifyColumnClause;
import com.starrocks.sql.ast.ModifyColumnCommentClause;
import com.starrocks.sql.ast.ModifyTablePropertiesClause;
import com.starrocks.sql.ast.TableRenameClause;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.schema.SchemaChange;
import org.apache.paimon.types.DataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies the Paimon schema changes that have a direct equivalent in the StarRocks ALTER TABLE AST.
 */
public class PaimonAlterTableExecutor extends ConnectorAlterTableExecutor {
    private final Catalog catalog;
    private final Identifier identifier;
    private final List<SchemaChange> schemaChanges = new ArrayList<>();
    private String renamedTable;

    public PaimonAlterTableExecutor(AlterTableStmt stmt, Catalog catalog) {
        super(stmt);
        this.catalog = catalog;
        this.identifier = Identifier.create(stmt.getDbName(), stmt.getTableName());
    }

    @Override
    public void applyClauses() throws DdlException {
        try {
            for (com.starrocks.sql.ast.AlterClause clause : stmt.getAlterClauseList()) {
                clause.accept(this, null);
            }
            if (renamedTable != null) {
                if (!schemaChanges.isEmpty()) {
                    throw new StarRocksConnectorException(
                            "Paimon table rename cannot be combined with schema changes in one ALTER TABLE statement");
                }
                catalog.renameTable(identifier, Identifier.create(identifier.getDatabaseName(), renamedTable), false);
            } else if (!schemaChanges.isEmpty()) {
                catalog.alterTable(identifier, schemaChanges, false);
            }
        } catch (Catalog.TableNotExistException e) {
            throw new DdlException("Paimon table " + identifier + " does not exist", e);
        } catch (Catalog.TableAlreadyExistException e) {
            throw new DdlException("Paimon table already exists: " + e.getMessage(), e);
        } catch (Catalog.ColumnAlreadyExistException e) {
            throw new DdlException("Paimon column already exists: " + e.getMessage(), e);
        } catch (Catalog.ColumnNotExistException e) {
            throw new DdlException("Paimon column does not exist: " + e.getMessage(), e);
        } catch (StarRocksConnectorException e) {
            throw new DdlException(e.getMessage(), e.getCause());
        } catch (RuntimeException e) {
            throw new DdlException("Failed to alter Paimon table " + identifier + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Void visitAddColumnClause(AddColumnClause clause, ConnectContext context) {
        addColumn(clause.getColumnDef(), clause.getColPos());
        return null;
    }

    @Override
    public Void visitAddColumnsClause(AddColumnsClause clause, ConnectContext context) {
        for (ColumnDef columnDef : clause.getColumnDefs()) {
            addColumn(columnDef, null);
        }
        return null;
    }

    @Override
    public Void visitDropColumnClause(DropColumnClause clause, ConnectContext context) {
        schemaChanges.add(SchemaChange.dropColumn(clause.getColName()));
        return null;
    }

    @Override
    public Void visitColumnRenameClause(ColumnRenameClause clause, ConnectContext context) {
        schemaChanges.add(SchemaChange.renameColumn(clause.getColName(), clause.getNewColName()));
        return null;
    }

    @Override
    public Void visitModifyColumnClause(ModifyColumnClause clause, ConnectContext context) {
        ColumnDef columnDef = clause.getColumnDef();
        validateColumnDefinition(columnDef);
        Column column = columnDef.toColumn(null);
        DataType type = PaimonTypeConverter.toPaimonType(column.getType()).copy(column.isAllowNull());
        schemaChanges.add(SchemaChange.updateColumnType(column.getName(), type));
        schemaChanges.add(SchemaChange.updateColumnNullability(column.getName(), column.isAllowNull()));
        if (column.getComment() != null) {
            schemaChanges.add(SchemaChange.updateColumnComment(column.getName(), column.getComment()));
        }
        addMove(column.getName(), clause.getColPos());
        return null;
    }

    @Override
    public Void visitModifyColumnCommentClause(ModifyColumnCommentClause clause, ConnectContext context) {
        schemaChanges.add(SchemaChange.updateColumnComment(clause.getColumnName(), clause.getComment()));
        return null;
    }

    @Override
    public Void visitModifyTablePropertiesClause(ModifyTablePropertiesClause clause, ConnectContext context) {
        for (Map.Entry<String, String> entry : clause.getProperties().entrySet()) {
            if (entry.getValue() == null) {
                schemaChanges.add(SchemaChange.removeOption(entry.getKey()));
            } else {
                schemaChanges.add(SchemaChange.setOption(entry.getKey(), entry.getValue()));
            }
        }
        return null;
    }

    @Override
    public Void visitAlterTableCommentClause(AlterTableCommentClause clause, ConnectContext context) {
        schemaChanges.add(SchemaChange.updateComment(clause.getNewComment()));
        return null;
    }

    @Override
    public Void visitTableRenameClause(TableRenameClause clause, ConnectContext context) {
        renamedTable = clause.getNewTableName();
        return null;
    }

    private void addColumn(ColumnDef columnDef, ColumnPosition position) {
        validateColumnDefinition(columnDef);
        Column column = columnDef.toColumn(null);
        DataType type = PaimonTypeConverter.toPaimonType(column.getType()).copy(column.isAllowNull());
        SchemaChange.Move move = toMove(column.getName(), position);
        if (move == null) {
            schemaChanges.add(SchemaChange.addColumn(column.getName(), type, column.getComment()));
        } else {
            schemaChanges.add(SchemaChange.addColumn(column.getName(), type, column.getComment(), move));
        }
    }

    private void addMove(String columnName, ColumnPosition position) {
        SchemaChange.Move move = toMove(columnName, position);
        if (move != null) {
            schemaChanges.add(SchemaChange.updateColumnPosition(move));
        }
    }

    private SchemaChange.Move toMove(String columnName, ColumnPosition position) {
        if (position == null) {
            return null;
        }
        if (position.isFirst()) {
            return SchemaChange.Move.first(columnName);
        }
        if (position.getLastCol() != null) {
            return SchemaChange.Move.after(columnName, position.getLastCol());
        }
        throw new StarRocksConnectorException("Unsupported Paimon column position: " + position);
    }

    private void validateColumnDefinition(ColumnDef columnDef) {
        if (columnDef.isGeneratedColumn()) {
            throw new StarRocksConnectorException("Paimon does not support generated columns");
        }
        if (columnDef.isAutoIncrement()) {
            throw new StarRocksConnectorException("Paimon does not support AUTO_INCREMENT columns");
        }
        if (columnDef.getDefaultValueDef().isSet) {
            throw new StarRocksConnectorException("Paimon DDL does not support column default values");
        }
    }
}
