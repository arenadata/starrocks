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

#pragma once

#include <map>
#include <memory>
#include <string>
#include <vector>

#include "common/object_pool.h"
#include "common/statusor.h"
#include "exec/hdfs_scanner.h"

namespace arrow {
class DataType;
class RecordBatch;
} // namespace arrow

namespace paimon {
class BatchReader;
class MemoryPool;
class Split;
class TableRead;
} // namespace paimon

namespace starrocks {

class Expr;
struct ConvertFuncTree;

namespace paimon_cpp {
class PaimonStarRocksFileSystem;
}

Status validate_paimon_split_base64(const std::string& encoded_split, std::string* decoded_split);
StatusOr<std::shared_ptr<paimon::Split>> deserialize_paimon_split(
        const std::string& encoded_split, const std::shared_ptr<paimon::MemoryPool>& memory_pool);

class PaimonCppScanner final : public HdfsScanner {
public:
    PaimonCppScanner();
    ~PaimonCppScanner() override;

    Status do_init(RuntimeState* runtime_state, const HdfsScannerParams& scanner_params) override;
    Status do_open(RuntimeState* runtime_state) override;
    Status do_get_next(RuntimeState* runtime_state, ChunkPtr* chunk) override;
    void do_close(RuntimeState* runtime_state) noexcept override;
    void do_update_counter(HdfsScanProfile* profile) override;
    int64_t estimated_mem_usage() const override;

private:
    struct ColumnConversion {
        SlotDescriptor* slot = nullptr;
        int field_index = -1;
        std::shared_ptr<arrow::DataType> arrow_type;
        std::unique_ptr<ConvertFuncTree> converter;
        Expr* cast_expr = nullptr;
        ColumnPtr column_prototype;
    };

    Status _next_record_batch();
    Status _initialize_schema(const std::shared_ptr<arrow::RecordBatch>& batch);
    Status _convert_batch(RuntimeState* runtime_state, ChunkPtr* chunk, size_t offset, size_t row_count);
    std::vector<std::string> _build_projection() const;
    std::map<std::string, std::string> _build_options() const;

    std::shared_ptr<paimon::MemoryPool> _memory_pool;
    std::shared_ptr<paimon_cpp::PaimonStarRocksFileSystem> _file_system;
    std::shared_ptr<paimon::Split> _split;
    std::unique_ptr<paimon::TableRead> _table_read;
    std::unique_ptr<paimon::BatchReader> _batch_reader;
    std::shared_ptr<arrow::RecordBatch> _record_batch;
    size_t _batch_offset = 0;

    ObjectPool _conversion_pool;
    std::vector<ColumnConversion> _conversions;
    Filter _conversion_filter;
    Filter _predicate_filter;
    bool _schema_initialized = false;
    bool _skip_split = false;
    bool _eof = false;
    bool _closed = false;
    int64_t _arrow_import_ns = 0;
    int64_t _arrow_record_batches = 0;
};

} // namespace starrocks
