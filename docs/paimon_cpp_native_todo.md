# Paimon C++ native-reader graduation TODO

The current C++ reader is an experimental, read-only path for eligible Paimon `DataSplit` ranges. It is not the replacement for the JNI reader. This document records the native-only work that must be complete before that status can change.

## Current boundary

- The C++ path is compiled only with `ENABLE_PAIMON_CPP=ON` and is off by default.
- `enable_paimon_cpp_reader` opts a session into eligible logical `DataSplit` ranges; `paimon_force_jni_reader` independently overrides it and always selects JNI.
- Raw Parquet and ORC Paimon files use the existing native StarRocks file readers. Paimon C++ is built with its ORC implementation disabled, so logical ORC splits must remain on JNI.
- System-table and other non-`DataSplit` Paimon scans remain on JNI.
- A BE/CN without the C++ capability rejects a C++ range, and a C++ runtime failure does not automatically retry through JNI. Rollout therefore requires a homogeneous executable pool or the force-JNI rollback.

## Remaining native-only work

1. **Dynamic and postpone buckets**
   - Implement and qualify native execution for dynamic-bucket and postpone-bucket Paimon tables, including their split, merge, and visibility semantics.
   - Until then, keep these table modes on JNI. Do not treat a successful fixed-bucket scan as qualification for either mode.

2. **ORC**
   - Add the Paimon C++ ORC reader and link it against a compatible ORC dependency set.
   - Preserve the current raw-ORC native reader behavior and add logical-split ORC support only after C++/JNI result parity is established.

3. **Inline compaction and spill**
   - Support Paimon reads that encounter inline compaction output and any reader-side spill path.
   - Define memory accounting, cancellation, cleanup, and retry behavior for spill files. The implementation must not leak temporary files or bypass StarRocks memory tracking.

4. **Arrow 19 deprecated APIs**
   - Replace Paimon C++ calls to deprecated Arrow 19 out-parameter APIs, including `parquet::arrow::FileReader::GetRecordBatchReader(..., out*)`, with result-returning APIs and equivalent error handling.
   - Remove `-Wno-deprecated-declarations` from both `thirdparty/build-thirdparty.sh` and `thirdparty/build-thirdparty-darwin.sh` after the upstream or vendored fix is present.

5. **Eventual JNI removal**
   - Do not remove JNI dispatch, the JNI reader package, or `paimon_force_jni_reader` until every supported Paimon read mode and recovery case below has passed its graduation tests in a release-qualified build.
   - Keep JNI for unsupported formats, table modes, system tables, and capability-mismatched nodes until their native equivalents are explicitly graduated.

## Graduation test matrix

The following tests are required before expanding the C++ eligibility boundary or removing JNI fallback support:

1. **Reader selection and capability**
   - Verify the default session selects JNI for logical Paimon splits.
   - Verify the C++ opt-in selects C++ only for eligible `DataSplit` ranges; verify force-JNI wins when both flags are enabled.
   - Verify non-`DataSplit` and system-table scans remain JNI.
   - Verify a BE/CN without `ENABLE_PAIMON_CPP` rejects a C++ range clearly and cannot execute it through an accidental native or JNI path.

2. **Catalog and SQL parity**
   - Run `SHOW DATABASES`, `SHOW TABLES`, `DESC`, `SHOW CREATE TABLE`, and `SELECT` against filesystem and Hive catalogs.
   - Compare C++ and JNI results for projection, filters, partition predicates, nested types, nulls, schema evolution, primary-key tables, append tables, deletion files, and multiple snapshots.
   - Confirm Paimon-targeting write and DDL statements remain rejected and that `INSERT INTO <StarRocks table> SELECT ... FROM <Paimon table>` remains unchanged.

3. **Bucket-mode graduation**
   - For fixed, dynamic, and postpone buckets, compare C++ and JNI row counts, checksums, and predicate results before and after compaction.
   - Exercise bucket changes, concurrent commits, empty splits, split reassignment, and snapshot changes. A mode may be enabled only after all cases pass.

4. **Format graduation**
   - For Parquet and, once implemented, ORC, compare logical-split C++ results with JNI for partitioned and unpartitioned tables, deletes, nested columns, decimal and timestamp values, projection, and predicates.
   - Confirm raw-file Parquet and ORC routing remains unchanged.

5. **Inline compaction, spill, and recovery**
   - Force inline compaction while scans are running and verify snapshot-consistent, duplicate-free results for both readers.
   - Force memory pressure and spill; verify memory limits, cancellation, cleanup, and no leaked spill files.
   - Inject reader, object-store, and split-deserialization failures. Verify no Paimon write is issued, no false success is returned, and a separately resubmitted JNI query gives the expected result for its planned snapshot.

6. **Arrow and packaging**
   - Build the full third-party chain with Arrow 19 and Paimon C++ under warnings-as-errors after removing the deprecation suppression.
   - Verify static link closure, a C++-capable BE startup, and all-in-one image packaging.

7. **Performance and observability**
   - Compare C++ and JNI scan latency, throughput, memory, cancellation latency, and error rate on representative production data.
   - Verify `Paimon.metadata.reader.<table>.{native,jni,cpp}ReaderReadNum` and `...ReadBytes` agree with planned reader routing and that the C++ BE profile's `PaimonCpp.ArrowRecordBatches` and `PaimonCpp.ArrowImportTime` counters agree with the scan workload.

JNI removal is permitted only when every row above is automated where feasible, has passed across supported platforms and storage backends, and the operational rollback story no longer depends on JNI.
