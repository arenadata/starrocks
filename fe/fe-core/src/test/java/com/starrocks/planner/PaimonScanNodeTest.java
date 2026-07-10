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

import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotId;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.analysis.TupleId;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.PaimonTable;
import com.starrocks.catalog.Type;
import com.starrocks.connector.CatalogConnector;
import com.starrocks.credential.CloudConfiguration;
import com.starrocks.credential.CloudConfigurationFactory;
import com.starrocks.qe.SessionVariable;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.thrift.THdfsFileFormat;
import com.starrocks.thrift.THdfsScanRange;
import com.starrocks.thrift.TPaimonReaderType;
import com.starrocks.thrift.TScanRangeLocations;
import mockit.Expectations;
import mockit.Mocked;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.BinaryRowWriter;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.io.DataInputViewStreamWrapper;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.DeletionFile;
import org.apache.paimon.table.source.RawFile;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.utils.InstantiationUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.paimon.io.DataFileMeta.DUMMY_LEVEL;
import static org.apache.paimon.io.DataFileMeta.EMPTY_MAX_KEY;
import static org.apache.paimon.io.DataFileMeta.EMPTY_MIN_KEY;
import static org.apache.paimon.stats.SimpleStats.EMPTY_STATS;

public class PaimonScanNodeTest {
    @Test
    public void testInit(@Mocked GlobalStateMgr globalStateMgr,
                         @Mocked CatalogConnector connector,
                         @Mocked PaimonTable table) {
        String catalog = "XXX";
        CloudConfiguration cc = CloudConfigurationFactory.buildCloudConfigurationForStorage(new HashMap<>());
        new Expectations() {
            {
                GlobalStateMgr.getCurrentState().getConnectorMgr().getConnector(catalog);
                result = connector;
                connector.getMetadata().getCloudConfiguration();
                result = cc;
                table.getCatalogName();
                result = catalog;
            }
        };
        TupleDescriptor desc = new TupleDescriptor(new TupleId(0));
        desc.setTable(table);
        PaimonScanNode scanNode = new PaimonScanNode(new PlanNodeId(0), desc, "XXX");
    }

    @Test
    public void testTotalFileLength(@Mocked PaimonTable table) {
        BinaryRow row1 = new BinaryRow(2);
        BinaryRowWriter writer = new BinaryRowWriter(row1, 10);
        writer.writeInt(0, 2000);
        writer.writeInt(1, 4444);
        writer.complete();

        List<DataFileMeta> meta1 = new ArrayList<>();
        meta1.add(DataFileMeta.create("file1", 100L, 200L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 200L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));
        meta1.add(DataFileMeta.create("file2", 100L, 300L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 300L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));

        DataSplit split = DataSplit.builder().withSnapshot(1L).withPartition(row1).withBucket(1)
                .withBucketPath("not used").withDataFiles(meta1).isStreaming(false).build();

        TupleDescriptor desc = new TupleDescriptor(new TupleId(0));
        desc.setTable(table);
        PaimonScanNode scanNode = new PaimonScanNode(new PlanNodeId(0), desc, "XXX");
        long totalFileLength = scanNode.getTotalFileLength(split);

        Assertions.assertEquals(200, totalFileLength);
    }

    @Test
    public void testEstimatedLength(@Mocked PaimonTable table) {
        BinaryRow row1 = new BinaryRow(2);
        BinaryRowWriter writer = new BinaryRowWriter(row1, 10);
        writer.writeInt(0, 2000);
        writer.writeInt(1, 4444);
        writer.complete();

        List<DataFileMeta> meta1 = new ArrayList<>();
        meta1.add(DataFileMeta.create("file1", 100L, 200L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 200L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));
        meta1.add(DataFileMeta.create("file2", 100L, 300L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 300L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));

        DataSplit split = DataSplit.builder().withSnapshot(1L).withPartition(row1).withBucket(1)
                .withBucketPath("not used").withDataFiles(meta1).isStreaming(false).build();

        TupleDescriptor desc = new TupleDescriptor(new TupleId(0));
        desc.setTable(table);
        SlotDescriptor slot1 = new SlotDescriptor(new SlotId(1), "id", Type.INT, false);
        slot1.setColumn(new Column("id", Type.INT));
        SlotDescriptor slot2 = new SlotDescriptor(new SlotId(2), "name", Type.STRING, false);
        slot2.setColumn(new Column("name", Type.STRING));
        desc.addSlot(slot1);
        desc.addSlot(slot2);
        PaimonScanNode scanNode = new PaimonScanNode(new PlanNodeId(0), desc, "XXX");
        long totalFileLength = scanNode.getEstimatedLength(split.rowCount(), desc);
        Assertions.assertEquals(10000, totalFileLength);
    }

    @Test
    public void testSplitRawFileScanRange(@Mocked PaimonTable table, @Mocked RawFile rawFile) {
        BinaryRow row1 = new BinaryRow(2);
        BinaryRowWriter writer = new BinaryRowWriter(row1, 10);
        writer.writeInt(0, 2000);
        writer.writeInt(1, 4444);
        writer.complete();

        List<DataFileMeta> meta1 = new ArrayList<>();

        meta1.add(DataFileMeta.create("file1", 100L, 200L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 200L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));
        meta1.add(DataFileMeta.create("file2", 100L, 300L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 300L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));

        DataSplit split = DataSplit.builder().withSnapshot(1L).withPartition(row1).withBucket(1)
                .withBucketPath("not used").withDataFiles(meta1).isStreaming(false).build();
        TupleDescriptor desc = new TupleDescriptor(new TupleId(0));
        new Expectations() {
            {
                rawFile.format();
                result = "orc";
            }
        };
        desc.setTable(table);
        PaimonScanNode scanNode = new PaimonScanNode(new PlanNodeId(0), desc, "XXX");
        DeletionFile deletionFile = new DeletionFile("dummy", 1, 22, 0L);
        scanNode.splitRawFileScanRangeLocations(rawFile, deletionFile);
        scanNode.splitScanRangeLocations(rawFile, 0, 256 * 1024 * 1024, 64 * 1024 * 1024, null);
        scanNode.addSplitScanRangeLocations(split, null, 256 * 1024 * 1024);
        Assertions.assertEquals(6, scanNode.getScanRangeLocations(10).size());
        for (int i = 0; i < 5; i++) {
            THdfsScanRange scanRange =
                    scanNode.getScanRangeLocations(10).get(i).getScan_range().getHdfs_scan_range();
            Assertions.assertEquals(TPaimonReaderType.PAIMON_NATIVE, scanRange.getPaimon_reader_type());
            Assertions.assertFalse(scanRange.isUse_paimon_jni_reader());
        }
        THdfsScanRange jniScanRange =
                scanNode.getScanRangeLocations(10).get(5).getScan_range().getHdfs_scan_range();
        Assertions.assertEquals(TPaimonReaderType.PAIMON_JNI, jniScanRange.getPaimon_reader_type());
        Assertions.assertTrue(jniScanRange.isUse_paimon_jni_reader());
    }

    @Test
    public void testAddSplitScanRangeLocations(@Mocked PaimonTable table) throws Exception {
        BinaryRow row1 = new BinaryRow(2);
        BinaryRowWriter writer = new BinaryRowWriter(row1, 10);
        writer.writeInt(0, 2000);
        writer.writeInt(1, 4444);
        writer.complete();

        List<DataFileMeta> meta1 = new ArrayList<>();

        meta1.add(DataFileMeta.create("file1", 100L, 200L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 200L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));
        meta1.add(DataFileMeta.create("file2", 100L, 300L, EMPTY_MIN_KEY, EMPTY_MAX_KEY, 
                EMPTY_STATS, EMPTY_STATS, 100L, 300L, 1L, DUMMY_LEVEL, 0L, null, null, null, null, null));

        DataSplit split = DataSplit.builder().withSnapshot(1L).withPartition(row1).withBucket(1)
                .withBucketPath("not used").withDataFiles(meta1).isStreaming(false).build();
        TupleDescriptor desc = new TupleDescriptor(new TupleId(0));
        desc.setTable(table);
        Map<String, String> options = Map.of("bucket", "2");
        new Expectations() {
            {
                table.getTableLocation();
                result = "s3://warehouse/db/table";
                table.getProperties();
                result = options;
            }
        };
        PaimonScanNode scanNode = new PaimonScanNode(new PlanNodeId(0), desc, "XXX");
        scanNode.addSplitScanRangeLocations(split, null, 256 * 1024 * 1024, TPaimonReaderType.PAIMON_CPP);
        scanNode.addSplitScanRangeLocations(split, null, 256 * 1024 * 1024, TPaimonReaderType.PAIMON_JNI);

        Assertions.assertEquals(2, scanNode.getScanRangeLocations(10).size());
        TScanRangeLocations cppLocations = scanNode.getScanRangeLocations(10).get(0);
        THdfsScanRange cppRange = cppLocations.getScan_range().getHdfs_scan_range();
        Assertions.assertEquals(THdfsFileFormat.UNKNOWN, cppRange.getFile_format());
        Assertions.assertEquals(TPaimonReaderType.PAIMON_CPP, cppRange.getPaimon_reader_type());
        Assertions.assertFalse(cppRange.isUse_paimon_jni_reader());
        Assertions.assertEquals("s3://warehouse/db/table", cppRange.getPaimon_table_path());
        Assertions.assertEquals(options, cppRange.getPaimon_table_options());
        byte[] nativePayload = Base64.getDecoder().decode(cppRange.getPaimon_split_info());
        DataSplit decodedNativeSplit = DataSplit.deserialize(
                new DataInputViewStreamWrapper(new ByteArrayInputStream(nativePayload)));
        Assertions.assertEquals(split.snapshotId(), decodedNativeSplit.snapshotId());
        Assertions.assertEquals(split.bucket(), decodedNativeSplit.bucket());
        Assertions.assertEquals(split.dataFiles().size(), decodedNativeSplit.dataFiles().size());

        THdfsScanRange jniRange =
                scanNode.getScanRangeLocations(10).get(1).getScan_range().getHdfs_scan_range();
        Assertions.assertEquals(TPaimonReaderType.PAIMON_JNI, jniRange.getPaimon_reader_type());
        Assertions.assertTrue(jniRange.isUse_paimon_jni_reader());
        Assertions.assertFalse(jniRange.isSetPaimon_table_path());
        Assertions.assertFalse(jniRange.isSetPaimon_table_options());
        byte[] jniPayload = Base64.getUrlDecoder().decode(jniRange.getPaimon_split_info());
        DataSplit decodedJniSplit = InstantiationUtil.deserializeObject(
                jniPayload, PaimonScanNodeTest.class.getClassLoader());
        Assertions.assertEquals(split.snapshotId(), decodedJniSplit.snapshotId());
        Assertions.assertEquals(split.bucket(), decodedJniSplit.bucket());
    }

    @Test
    public void testReaderModeSelection(@Mocked PaimonTable table, @Mocked Split systemTableSplit) {
        TupleDescriptor desc = new TupleDescriptor(new TupleId(0));
        desc.setTable(table);
        PaimonScanNode scanNode = new PaimonScanNode(new PlanNodeId(0), desc, "XXX");
        DataSplit dataSplit = DataSplit.builder()
                .withSnapshot(1L)
                .withPartition(new BinaryRow(0))
                .withBucket(1)
                .withBucketPath("not used")
                .withDataFiles(new ArrayList<>())
                .isStreaming(false)
                .build();
        SessionVariable sessionVariable = new SessionVariable();

        Assertions.assertEquals(TPaimonReaderType.PAIMON_JNI,
                scanNode.selectPaimonReaderType(dataSplit, sessionVariable));
        sessionVariable.setEnablePaimonCppReader(true);
        Assertions.assertEquals(TPaimonReaderType.PAIMON_CPP,
                scanNode.selectPaimonReaderType(dataSplit, sessionVariable));
        Assertions.assertEquals(TPaimonReaderType.PAIMON_JNI,
                scanNode.selectPaimonReaderType(systemTableSplit, sessionVariable));
        sessionVariable.setPaimonForceJNIReader(true);
        Assertions.assertEquals(TPaimonReaderType.PAIMON_JNI,
                scanNode.selectPaimonReaderType(dataSplit, sessionVariable));
    }
}