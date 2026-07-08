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
import com.starrocks.catalog.Column;
import com.starrocks.catalog.PaimonTable;
import com.starrocks.common.util.CompressionUtils;
import com.starrocks.connector.Connector;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.qe.ConnectContext;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.TCloudConfiguration;
import com.starrocks.thrift.TCompressionType;
import com.starrocks.thrift.TDataSink;
import com.starrocks.thrift.TDataSinkType;
import com.starrocks.thrift.TExplainLevel;
import com.starrocks.thrift.TPaimonTableSink;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class PaimonTableSink extends DataSink {
    private final TupleDescriptor desc;
    private final String fileFormat;
    private final String stagingDir;
    private final List<String> dataColNames;
    private final List<String> partitionColNames;
    private final String compressionType;
    private final long targetMaxFileSize;
    private final boolean isStaticPartitionSink;
    private final String tableIdentifier;
    private final CloudConfiguration cloudConfiguration;

    public PaimonTableSink(PaimonTable paimonTable, TupleDescriptor desc,
                           boolean isStaticPartitionSink, SessionVariable sessionVariable) {
        this.desc = desc;
        this.partitionColNames = paimonTable.getPartitionColumnNames();
        this.dataColNames = paimonTable.getBaseSchema().stream()
                .map(Column::getName)
                .filter(col -> !partitionColNames.contains(col))
                .collect(Collectors.toList());
        this.tableIdentifier = paimonTable.getUUID();
        this.isStaticPartitionSink = isStaticPartitionSink;
        this.fileFormat = paimonTable.getProperties().getOrDefault("file.format", "parquet").toLowerCase();
        if (!fileFormat.equals("parquet") && !fileFormat.equals("orc")) {
            throw new StarRocksConnectorException("Writing to paimon table in [%s] format is not supported.", fileFormat);
        }
        this.compressionType = paimonTable.getProperties().getOrDefault("compression_codec",
                sessionVariable.getConnectorSinkCompressionCodec());
        this.targetMaxFileSize = sessionVariable.getConnectorSinkTargetMaxFileSize() > 0
                ? sessionVariable.getConnectorSinkTargetMaxFileSize()
                : 1024L * 1024 * 1024;
        this.stagingDir = buildStagingDir(paimonTable);

        String catalogName = paimonTable.getCatalogName();
        Connector connector = GlobalStateMgr.getCurrentState().getConnectorMgr().getConnector(catalogName);
        Preconditions.checkState(connector != null,
                String.format("connector of catalog %s should not be null", catalogName));
        this.cloudConfiguration = connector.getMetadata().getCloudConfiguration();
        Preconditions.checkState(cloudConfiguration != null,
                String.format("cloudConfiguration of catalog %s should not be null", catalogName));
    }

    private String buildStagingDir(PaimonTable paimonTable) {
        String queryId = ConnectContext.get() != null && ConnectContext.get().getQueryId() != null
                ? ConnectContext.get().getQueryId().toString()
                : UUID.randomUUID().toString();
        return paimonTable.getTableLocation() + "/.starrocks/staging/" + queryId;
    }

    @Override
    public String getExplainString(String prefix, TExplainLevel explainLevel) {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append(prefix).append("Paimon TABLE SINK\n");
        strBuilder.append(prefix).append("  TABLE: ").append(tableIdentifier).append("\n");
        strBuilder.append(prefix).append("  TUPLE ID: ").append(desc.getId()).append("\n");
        strBuilder.append(prefix).append("  ").append(DataPartition.RANDOM.getExplainString(explainLevel));
        return strBuilder.toString();
    }

    @Override
    protected TDataSink toThrift() {
        TDataSink tDataSink = new TDataSink(TDataSinkType.PAIMON_TABLE_SINK);
        TPaimonTableSink tPaimonTableSink = new TPaimonTableSink();
        tPaimonTableSink.setData_column_names(dataColNames);
        tPaimonTableSink.setPartition_column_names(partitionColNames);
        tPaimonTableSink.setStaging_dir(stagingDir);
        tPaimonTableSink.setFile_format(fileFormat);
        tPaimonTableSink.setIs_static_partition_sink(isStaticPartitionSink);
        Preconditions.checkState(CompressionUtils.getConnectorSinkCompressionType(compressionType).isPresent());
        TCompressionType compression = CompressionUtils.getConnectorSinkCompressionType(compressionType).get();
        tPaimonTableSink.setCompression_type(compression);
        tPaimonTableSink.setTarget_max_file_size(targetMaxFileSize);
        TCloudConfiguration tCloudConfiguration = new TCloudConfiguration();
        cloudConfiguration.toThrift(tCloudConfiguration);
        tPaimonTableSink.setCloud_configuration(tCloudConfiguration);
        tDataSink.setPaimon_table_sink(tPaimonTableSink);
        return tDataSink;
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

    public String getStagingDir() {
        return stagingDir;
    }
}
