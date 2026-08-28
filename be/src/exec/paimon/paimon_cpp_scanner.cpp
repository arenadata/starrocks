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

#include "exec/paimon/paimon_cpp_scanner.h"

#include <arrow/c/bridge.h>
#include <arrow/record_batch.h>

#include <algorithm>
#include <cctype>
#include <limits>
#include <unordered_map>
#include <unordered_set>

#include "column/chunk.h"
#include "column/column_helper.h"
#include "exec/paimon/paimon_memory_pool.h"
#include "exec/paimon/paimon_starrocks_file_system.h"
#include "exec/parquet_scanner.h"
#include "exprs/expr.h"
#include "paimon/memory/memory_pool.h"
#include "paimon/read_context.h"
#include "paimon/reader/batch_reader.h"
#include "paimon/table/source/split.h"
#include "paimon/table/source/table_read.h"
#include "runtime/runtime_state.h"
#include "util/url_coding.h"

namespace starrocks {
namespace {

constexpr std::string_view kValueKindField = "_VALUE_KIND";

std::string normalize_name(std::string_view name, bool case_sensitive) {
    if (case_sensitive) {
        return std::string(name);
    }
    std::string result(name);
    std::transform(result.begin(), result.end(), result.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return result;
}

} // namespace

Status validate_paimon_split_base64(const std::string& encoded_split, std::string* decoded_split) {
    if (decoded_split == nullptr) {
        return Status::InvalidArgument("Paimon split output is null");
    }
    decoded_split->clear();
    if (encoded_split.empty()) {
        return Status::InvalidArgument("Paimon C++ reader requires non-empty paimon_split_info");
    }
    if (encoded_split.size() % 4 != 0) {
        return Status::InvalidArgument("Paimon split is not standard padded Base64");
    }

    size_t first_padding = encoded_split.find('=');
    if (first_padding != std::string::npos) {
        const size_t padding = encoded_split.size() - first_padding;
        if (padding > 2 || first_padding < encoded_split.size() - 2) {
            return Status::InvalidArgument("Paimon split has invalid Base64 padding");
        }
    }
    const size_t payload_end = first_padding == std::string::npos ? encoded_split.size() : first_padding;
    for (size_t i = 0; i < payload_end; ++i) {
        const unsigned char c = encoded_split[i];
        if (!std::isalnum(c) && c != '+' && c != '/') {
            return Status::InvalidArgument("Paimon split contains a non-Base64 character");
        }
    }
    if (!base64_decode(encoded_split, decoded_split) || decoded_split->empty()) {
        decoded_split->clear();
        return Status::InvalidArgument("Failed to decode Paimon split from Base64");
    }
    return Status::OK();
}

StatusOr<std::shared_ptr<paimon::Split>> deserialize_paimon_split(
        const std::string& encoded_split, const std::shared_ptr<paimon::MemoryPool>& memory_pool) {
    if (memory_pool == nullptr) {
        return Status::InvalidArgument("Paimon memory pool is null");
    }
    std::string decoded;
    RETURN_IF_ERROR(validate_paimon_split_base64(encoded_split, &decoded));
    auto result = paimon::Split::Deserialize(decoded.data(), decoded.size(), memory_pool);
    if (!result.ok()) {
        return paimon_cpp::from_paimon_status(result.status(), "deserialize Paimon split");
    }
    if (result.value() == nullptr) {
        return Status::InvalidArgument("Paimon split deserializer returned null");
    }
    return std::move(result).value();
}

PaimonCppScanner::PaimonCppScanner() = default;

PaimonCppScanner::~PaimonCppScanner() {
    do_close(_runtime_state);
}

Status PaimonCppScanner::do_init(RuntimeState* /*runtime_state*/, const HdfsScannerParams& scanner_params) {
    if (scanner_params.scan_range == nullptr) {
        return Status::InvalidArgument("Paimon C++ reader requires an HDFS scan range");
    }
    const THdfsScanRange& scan_range = *scanner_params.scan_range;
    if (!scan_range.__isset.paimon_split_info || scan_range.paimon_split_info.empty()) {
        return Status::InvalidArgument("Paimon C++ reader requires non-empty paimon_split_info");
    }
    if (!scan_range.__isset.paimon_table_path || scan_range.paimon_table_path.empty()) {
        return Status::InvalidArgument("Paimon C++ reader requires non-empty paimon_table_path");
    }
    std::string decoded;
    return validate_paimon_split_base64(scan_range.paimon_split_info, &decoded);
}

Status PaimonCppScanner::do_open(RuntimeState* runtime_state) {
    RETURN_IF_CANCELLED(runtime_state);
    const THdfsScanRange& scan_range = *_scanner_params.scan_range;

    _memory_pool = std::make_shared<PaimonMemoryPool>(runtime_state->instance_mem_tracker_ptr());
    ASSIGN_OR_RETURN(_split, deserialize_paimon_split(scan_range.paimon_split_info, _memory_pool));

    const auto options = _build_options();
    _file_system = std::make_shared<paimon_cpp::PaimonStarRocksFileSystem>(
            options, _scanner_params.cloud_configuration, runtime_state, &_app_stats);

    paimon::ReadContextBuilder builder(scan_range.paimon_table_path);
    const auto projection = _build_projection();
    if (!projection.empty()) {
        builder.SetReadSchema(projection);
    }
    if (!options.empty()) {
        builder.SetOptions(options);
    }
    builder.WithMemoryPool(_memory_pool);
    builder.WithFileSystem(_file_system);

    auto context = builder.Finish();
    if (!context.ok()) {
        return paimon_cpp::from_paimon_status(context.status(), "build Paimon read context");
    }
    auto table_read = paimon::TableRead::Create(std::move(context).value());
    if (!table_read.ok()) {
        return paimon_cpp::from_paimon_status(table_read.status(), "create Paimon table reader");
    }
    _table_read = std::move(table_read).value();

    auto batch_reader = _table_read->CreateReader(_split);
    if (!batch_reader.ok()) {
        return paimon_cpp::from_paimon_status(batch_reader.status(), "create Paimon batch reader");
    }
    _batch_reader = std::move(batch_reader).value();
    if (_batch_reader == nullptr) {
        return Status::InternalError("Paimon CreateReader returned null");
    }
    return Status::OK();
}

std::vector<std::string> PaimonCppScanner::_build_projection() const {
    std::vector<std::string> projection;
    projection.reserve(_scanner_ctx.materialized_columns.size());
    for (const auto& column : _scanner_ctx.materialized_columns) {
        projection.emplace_back(column.name());
    }
    return projection;
}

std::map<std::string, std::string> PaimonCppScanner::_build_options() const {
    const THdfsScanRange& scan_range = *_scanner_params.scan_range;
    if (!scan_range.__isset.paimon_table_options) {
        return {};
    }
    return scan_range.paimon_table_options;
}

Status PaimonCppScanner::_next_record_batch() {
    if (_eof) {
        return Status::EndOfFile("Paimon reader reached EOF");
    }
    if (_batch_reader == nullptr) {
        return Status::InternalError("Paimon batch reader is not initialized");
    }

    for (;;) {
        RETURN_IF_CANCELLED(_runtime_state);
        auto result = _batch_reader->NextBatch();
        if (!result.ok()) {
            return paimon_cpp::from_paimon_status(result.status(), "read Paimon batch");
        }
        auto batch = std::move(result).value();
        if (paimon::BatchReader::IsEofBatch(batch)) {
            _record_batch.reset();
            _batch_offset = 0;
            _eof = true;
            return Status::EndOfFile("Paimon reader reached EOF");
        }
        if (batch.first == nullptr || batch.second == nullptr) {
            return Status::InternalError("Paimon reader returned an incomplete Arrow C Data batch");
        }

        auto import_record_batch = [&]() {
            SCOPED_RAW_TIMER(&_arrow_import_ns);
            return arrow::ImportRecordBatch(batch.first.get(), batch.second.get());
        };
        auto imported = import_record_batch();
        if (!imported.ok()) {
            return Status::InvalidArgument(
                    "Failed to import Paimon Arrow C Data batch: " + paimon_cpp::redact_sensitive(imported.status().message()));
        }
        _record_batch = std::move(imported).ValueUnsafe();
        _batch_offset = 0;
        if (_record_batch == nullptr) {
            return Status::InternalError("Arrow import returned a null Paimon record batch");
        }
        ++_arrow_record_batches;
        if (_record_batch->num_rows() == 0) {
            _record_batch.reset();
            continue;
        }
        if (!_schema_initialized) {
            RETURN_IF_ERROR(_initialize_schema(_record_batch));
        } else {
            for (const auto& conversion : _conversions) {
                if (conversion.field_index < 0 || conversion.field_index >= _record_batch->num_columns() ||
                    !conversion.arrow_type->Equals(_record_batch->column(conversion.field_index)->type())) {
                    return Status::InvalidArgument("Paimon Arrow schema changed between batches");
                }
            }
        }
        return Status::OK();
    }
}

Status PaimonCppScanner::_initialize_schema(const std::shared_ptr<arrow::RecordBatch>& batch) {
    std::unordered_set<std::string> field_names;
    std::unordered_map<std::string, int> field_indexes;
    for (int i = 0; i < batch->num_columns(); ++i) {
        const std::string& name = batch->schema()->field(i)->name();
        if (name == kValueKindField) {
            continue;
        }
        const std::string normalized = normalize_name(name, _scanner_ctx.case_sensitive);
        if (!field_indexes.emplace(normalized, i).second) {
            return Status::InvalidArgument("Paimon Arrow batch contains duplicate column name: " + normalized);
        }
        field_names.emplace(normalized);
    }
    RETURN_IF_ERROR(_scanner_ctx.update_materialized_columns(field_names));
    ASSIGN_OR_RETURN(_skip_split, _scanner_ctx.should_skip_by_evaluating_not_existed_slots());
    if (_skip_split) {
        _schema_initialized = true;
        return Status::OK();
    }

    _conversions.reserve(_scanner_ctx.materialized_columns.size());
    for (const auto& column_info : _scanner_ctx.materialized_columns) {
        auto it = field_indexes.find(column_info.formatted_name(_scanner_ctx.case_sensitive));
        if (it == field_indexes.end()) {
            return Status::InternalError("Paimon materialized column disappeared during schema initialization");
        }

        ColumnConversion conversion;
        conversion.slot = column_info.slot_desc;
        conversion.field_index = it->second;
        conversion.arrow_type = batch->schema()->field(it->second)->type();
        conversion.converter = std::make_unique<ConvertFuncTree>();
        RETURN_IF_ERROR(ParquetScanner::new_column(conversion.arrow_type.get(), conversion.slot,
                                                   &conversion.column_prototype, conversion.converter.get(),
                                                   &conversion.cast_expr, _conversion_pool, false));
        if (conversion.column_prototype == nullptr || conversion.cast_expr == nullptr) {
            return Status::InternalError("Failed to initialize Paimon Arrow column conversion");
        }
        _conversions.emplace_back(std::move(conversion));
    }
    _schema_initialized = true;
    return Status::OK();
}

Status PaimonCppScanner::_convert_batch(RuntimeState* runtime_state, ChunkPtr* chunk, size_t offset,
                                        size_t row_count) {
    DCHECK(_record_batch != nullptr);
    DCHECK_LE(offset + row_count, static_cast<size_t>(_record_batch->num_rows()));
    _app_stats.raw_rows_read += row_count;

    ChunkPtr source_chunk = std::make_shared<Chunk>();
    _conversion_filter.assign(row_count, 1);
    ArrowConvertContext convert_context{
            .state = runtime_state,
            .current_slot = nullptr,
            .current_file = paimon_cpp::redact_sensitive(_scanner_params.scan_range->paimon_table_path)};

    for (const auto& conversion : _conversions) {
        ColumnPtr column = conversion.column_prototype->clone_empty();
        column->reserve(row_count);
        convert_context.current_slot = conversion.slot;
        RETURN_IF_ERROR(ParquetScanner::convert_array_to_column(
                conversion.converter.get(), row_count, _record_batch->column(conversion.field_index).get(), column,
                offset, 0, &_conversion_filter, &convert_context));
        source_chunk->append_column(std::move(column), conversion.slot->id());
    }

    size_t output_rows = row_count;
    if (!_conversions.empty()) {
        output_rows = source_chunk->filter(_conversion_filter);
        for (const auto& conversion : _conversions) {
            ASSIGN_OR_RETURN(auto column, conversion.cast_expr->evaluate_checked(nullptr, source_chunk.get()));
            column = ColumnHelper::unfold_const_column(conversion.slot->type(), output_rows, std::move(column));
            (*chunk)->append_or_update_column(std::move(column), conversion.slot->id());
        }
    }

    RETURN_IF_ERROR(_scanner_ctx.append_or_update_not_existed_columns_to_chunk(chunk, output_rows));
    _scanner_ctx.append_or_update_partition_column_to_chunk(chunk, output_rows);
    _scanner_ctx.append_or_update_extended_column_to_chunk(chunk, output_rows);
    (*chunk)->set_num_rows(output_rows);
    (*chunk)->check_or_die();

    if (output_rows > 0) {
        SCOPED_RAW_TIMER(&_app_stats.expr_filter_ns);
        RETURN_IF_ERROR(_scanner_ctx.evaluate_on_conjunct_ctxs_by_slot(chunk, &_predicate_filter));
    }
    return Status::OK();
}

Status PaimonCppScanner::do_get_next(RuntimeState* runtime_state, ChunkPtr* chunk) {
    if (_closed || _eof || _skip_split) {
        return Status::EndOfFile("Paimon reader reached EOF");
    }
    RETURN_IF_CANCELLED(runtime_state);
    if (_record_batch == nullptr || _batch_offset >= static_cast<size_t>(_record_batch->num_rows())) {
        _record_batch.reset();
        RETURN_IF_ERROR(_next_record_batch());
        if (_skip_split) {
            return Status::EndOfFile("Paimon split filtered by missing-column predicates");
        }
    }

    const size_t remaining = static_cast<size_t>(_record_batch->num_rows()) - _batch_offset;
    const size_t chunk_size = std::max<int>(runtime_state->chunk_size(), 1);
    const size_t rows = std::min(remaining, chunk_size);
    RETURN_IF_ERROR(_convert_batch(runtime_state, chunk, _batch_offset, rows));
    _batch_offset += rows;
    return Status::OK();
}

void PaimonCppScanner::do_close(RuntimeState* /*runtime_state*/) noexcept {
    if (_closed) {
        return;
    }
    _closed = true;
    // Arrow arrays may reference the reader's Paimon/Arrow memory adaptor, so
    // release imported batches before closing and destroying the reader.
    _record_batch.reset();
    if (_batch_reader != nullptr) {
        _batch_reader->Close();
    }
    _batch_reader.reset();
    _table_read.reset();
    _split.reset();
    _file_system.reset();
    _memory_pool.reset();
}

int64_t PaimonCppScanner::estimated_mem_usage() const {
    if (_memory_pool == nullptr) {
        return HdfsScanner::estimated_mem_usage();
    }
    return static_cast<int64_t>(
            std::min<uint64_t>(_memory_pool->CurrentUsage(), static_cast<uint64_t>(std::numeric_limits<int64_t>::max())));
}

void PaimonCppScanner::do_update_counter(HdfsScanProfile* profile) {
    static constexpr char kPaimonCppProfileSection[] = "PaimonCpp";
    RuntimeProfile* root = profile->runtime_profile;
    ADD_COUNTER(root, kPaimonCppProfileSection, TUnit::NONE);
    auto* arrow_record_batches =
            ADD_CHILD_COUNTER(root, "ArrowRecordBatches", TUnit::UNIT, kPaimonCppProfileSection);
    auto* arrow_import_timer =
            ADD_CHILD_COUNTER(root, "ArrowImportTime", TUnit::TIME_NS, kPaimonCppProfileSection);
    COUNTER_UPDATE(arrow_record_batches, _arrow_record_batches);
    COUNTER_UPDATE(arrow_import_timer, _arrow_import_ns);
}

} // namespace starrocks
