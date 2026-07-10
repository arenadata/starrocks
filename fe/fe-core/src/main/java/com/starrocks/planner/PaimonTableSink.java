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

package com.starrocks.planner;

import com.google.common.base.Preconditions;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.catalog.PaimonTable;
import com.starrocks.connector.CatalogConnector;
import com.starrocks.connector.paimon.PaimonDmlOperation;
import com.starrocks.connector.paimon.PaimonDmlSupport;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TCloudConfiguration;
import com.starrocks.thrift.TDataSink;
import com.starrocks.thrift.TDataSinkType;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.TPaimonTableSink;
import com.starrocks.thrift.TPaimonWriteMode;
import com.starrocks.thrift.TPaimonWriterType;

import java.util.Locale;
import java.util.UUID;

public class PaimonTableSink extends DataSink {
    private final PaimonTable table;
    private final TupleDescriptor desc;
    private final TPaimonWriterType writerType;
    private final TPaimonWriteMode writeMode;
    private final Integer rowKindSlotId;
    private final String commitUser;
    private final long commitId;
    private final CloudConfiguration cloudConfiguration;

    public PaimonTableSink(PaimonTable table, TupleDescriptor desc, String writerSelection, boolean overwrite,
                           String commitUser, UUID queryId) {
        this(table, desc, writerSelection, overwrite ? TPaimonWriteMode.OVERWRITE : TPaimonWriteMode.APPEND,
                null, commitUser, queryId);
    }

    public PaimonTableSink(PaimonTable table, TupleDescriptor desc, String writerSelection,
                           TPaimonWriteMode writeMode, Integer rowKindSlotId, String commitUser, UUID queryId) {
        this.table = table;
        this.desc = desc;
        this.writerType = parseWriterType(writerSelection);
        this.writeMode = writeMode;
        this.rowKindSlotId = rowKindSlotId;
        this.commitUser = commitUser;
        this.commitId = queryId == null ? 0 : queryId.getLeastSignificantBits();

        CatalogConnector connector = GlobalStateMgr.getCurrentState().getConnectorMgr().getConnector(table.getCatalogName());
        Preconditions.checkState(connector != null,
                String.format("connector of catalog %s should not be null", table.getCatalogName()));
        this.cloudConfiguration = connector.getMetadata().getCloudConfiguration();
        Preconditions.checkState(cloudConfiguration != null,
                String.format("cloudConfiguration of catalog %s should not be null", table.getCatalogName()));
    }

    public static PaimonTableSink createDmlSink(PaimonTable table, TupleDescriptor desc, PaimonDmlOperation operation) {
        ConnectContext context = ConnectContext.get();
        if (context == null) {
            throw new StarRocksConnectorException("Paimon row-level DML requires a connection context");
        }
        Integer rowKindSlot = desc.getSlots().stream()
                .filter(slot -> slot.getColumn() != null &&
                        PaimonDmlSupport.ROW_KIND_COLUMN.equals(slot.getColumn().getName()))
                .findFirst()
                .map(slot -> slot.getId().asInt())
                .orElseThrow(() -> new StarRocksConnectorException(
                        "Paimon row-level DML requires hidden %s output", PaimonDmlSupport.ROW_KIND_COLUMN));
        TPaimonWriteMode mode;
        switch (operation) {
            case INSERT:
                mode = TPaimonWriteMode.APPEND;
                break;
            case DELETE:
                mode = TPaimonWriteMode.DELETE;
                break;
            case UPDATE_BEFORE:
            case UPDATE_AFTER:
                mode = TPaimonWriteMode.UPDATE;
                break;
            case MERGE:
                mode = TPaimonWriteMode.MERGE;
                break;
            default:
                throw new StarRocksConnectorException("Unknown Paimon DML operation %s", operation);
        }
        return new PaimonTableSink(table, desc, context.getSessionVariable().getPaimonSinkWriter(), mode,
                rowKindSlot, context.getQualifiedUser(), context.getQueryId());
    }

    private static TPaimonWriterType parseWriterType(String writerSelection) {
        String selection = writerSelection == null ? "auto" : writerSelection.trim().toLowerCase(Locale.ROOT);
        switch (selection) {
            case "auto":
                return TPaimonWriterType.AUTO;
            case "cpp":
                return TPaimonWriterType.CPP;
            case "jni":
                return TPaimonWriterType.JNI;
            default:
                throw new StarRocksConnectorException(
                        "Unsupported paimon_sink_writer '%s'. Supported values are auto, cpp, and jni.", writerSelection);
        }
    }

    @Override
    public String getExplainString(String prefix, TExplainLevel explainLevel) {
        StringBuilder builder = new StringBuilder();
        builder.append(prefix).append("Paimon TABLE SINK\n");
        builder.append(prefix).append("  TABLE: ").append(table.getUUID()).append("\n");
        builder.append(prefix).append("  WRITER: ").append(writerType).append("\n");
        builder.append(prefix).append("  WRITE MODE: ").append(writeMode).append("\n");
        if (rowKindSlotId != null) {
            builder.append(prefix).append("  ROW KIND SLOT: ").append(rowKindSlotId).append("\n");
        }
        builder.append(prefix).append("  TUPLE ID: ").append(desc.getId()).append("\n");
        builder.append(prefix).append("  ").append(DataPartition.RANDOM.getExplainString(explainLevel));
        return builder.toString();
    }

    @Override
    protected TDataSink toThrift() {
        TPaimonTableSink paimonSink = new TPaimonTableSink();
        paimonSink.setTarget_table_id(table.getId());
        paimonSink.setTuple_id(desc.getId().asInt());
        paimonSink.setCatalog_name(table.getCatalogName());
        paimonSink.setDatabase_name(table.getCatalogDBName());
        paimonSink.setTable_name(table.getCatalogTableName());
        paimonSink.setTable_uuid(table.getUUID());
        paimonSink.setTable_location(table.getTableLocation());
        paimonSink.setTable_options(table.getProperties());
        paimonSink.setPartition_column_names(table.getPartitionColumnNames());
        paimonSink.setWriter_type(writerType);
        paimonSink.setWrite_mode(writeMode);
        if (rowKindSlotId != null) {
            paimonSink.setRow_kind_slot_id(rowKindSlotId);
        }
        paimonSink.setCommit_user(commitUser);
        paimonSink.setCommit_id(commitId);

        TCloudConfiguration thriftCloudConfiguration = new TCloudConfiguration();
        cloudConfiguration.toThrift(thriftCloudConfiguration);
        paimonSink.setCloud_configuration(thriftCloudConfiguration);

        TDataSink dataSink = new TDataSink(TDataSinkType.PAIMON_TABLE_SINK);
        dataSink.setPaimon_table_sink(paimonSink);
        return dataSink;
    }

    @Override
    public PlanNodeId getExchNodeId() {
        return null;
    }

    @Override
    public DataPartition getOutputPartition() {
        return null;
    }

    @Override
    public boolean canUseRuntimeAdaptiveDop() {
        return true;
    }
}
