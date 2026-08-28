# Paimon verification matrix

This matrix is an execution manifest for the Paimon verification suite. It deliberately
keeps unit tests local: no object store, Hive metastore, or running StarRocks cluster is
required for the FE and BE unit-test rows.

| Suite | Coverage | Test source or asset | CI lane |
| --- | --- | --- | --- |
| FE unit | DML row-kind envelope, hidden-column layout, serializer version, checksum, and empty-payload rejection | `PaimonDmlSupportTest`, `PaimonMetadataTest` | `fe-unit` |
| BE unit | JNI/native/C++ reader selection, unsupported capability, writer selection, invalid enum rejection | `paimon_reader_selector_test`, `paimon_sink_provider_test` | `be-unit` |
| BE unit (optional) | Base64 split validation, split deserialization failure, local filesystem, memory accounting, status mapping, cancellation | `paimon_cpp_adapter_test` | `be-unit-paimon-cpp` (`ENABLE_PAIMON_CPP=ON`) |
| FE integration | Paimon DDL type/partition mapping and cache invalidation; append, overwrite, abort, retry, and duplicate commit behavior | Paimon metadata integration scenarios | `paimon-integration` |
| Regression | Fixed/dynamic/postpone buckets, row kinds, snapshot visibility, Parquet/ORC routing, schema evolution | Paimon catalog regression cases | `paimon-regression` |
| Failure injection | Bad split payload, checksum/version mismatch, failed prepare/commit/abort, catalog invalidation error, cancellation, filesystem I/O and memory exhaustion | Unit tests plus fault-injection regression cases | `paimon-fault-injection` |

## Required matrix properties

- Run the FE and default BE unit lanes without external services.
- Run the optional C++ lane both with `ENABLE_PAIMON_CPP=OFF` (selection rejection) and
  `ENABLE_PAIMON_CPP=ON` (adapter tests).
- Execute each integration and regression scenario once through JNI and once through the
  eligible native/C++ reader, comparing rows, row kinds, bucket placement, and checksums.
- For commit retries, preserve the same writer ID and sequence so duplicate detection is
  exercised; use a new sequence only for a new payload.
- Inject failure before and after commit publication. Assert abort cleanup, cache
  invalidation, no duplicate rows, and an explicit terminal error rather than a silent retry.
