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

#include "runtime/paimon_table_sink.h"

#include <arrow/c/bridge.h>
#include <arrow/record_batch.h>

#include <algorithm>

#include "column/chunk.h"
#include "exec/pipeline/sink/paimon_table_sink_operator.h"
#include "exec/paimon/paimon_memory_pool.h"
#include "exec/paimon/paimon_sink_provider.h"
#include "exec/paimon/paimon_starrocks_file_system.h"
#include "exprs/expr.h"
#include "paimon/commit_message.h"
#include "paimon/file_store_write.h"
#include "paimon/record_batch.h"
#include "paimon/write_context.h"
#include "runtime/runtime_state.h"
#include "util/arrow/row_batch.h"
#include "util/arrow/starrocks_column_to_arrow.h"
#include "util/runtime_profile.h"
#include "util/sha.h"
#include "util/uid_util.h"

namespace starrocks {
namespace {
constexpr size_t kPaimonCommitPayloadBytes = 3 * 1024 * 1024;
}

PaimonTableSink::PaimonTableSink(ObjectPool* pool, const std::vector<TExpr>& output_exprs)
        : _pool(pool), _output_exprs(output_exprs) {}

PaimonTableSink::~PaimonTableSink() = default;

Status PaimonTableSink::init(const TDataSink& thrift_sink, RuntimeState* state) {
    if (!thrift_sink.__isset.paimon_table_sink) return Status::InternalError("Missing Paimon table sink");
    _sink = thrift_sink.paimon_table_sink;
    _write_mode = _sink.write_mode;
    if (_sink.__isset.row_kind_slot_id) _row_kind_slot_id = _sink.row_kind_slot_id;
    if ((_write_mode == TPaimonWriteMode::UPDATE || _write_mode == TPaimonWriteMode::DELETE ||
         _write_mode == TPaimonWriteMode::MERGE) &&
        _row_kind_slot_id < 0) {
        return Status::InvalidArgument("Paimon row-level DML requires hidden __paimon_row_kind output");
    }
    RETURN_IF_ERROR(DataSink::init(thrift_sink, state));
    RETURN_IF_ERROR(prepare(state));
    return open(state);
}

Status PaimonTableSink::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(DataSink::prepare(state));
    std::stringstream title;
    title << "PaimonTableSink (frag_id=" << state->fragment_instance_id() << ")";
    _profile = _pool->add(new RuntimeProfile(title.str()));
    return Status::OK();
}

Status PaimonTableSink::open(RuntimeState* state) {
    RETURN_IF_ERROR(Expr::open(_output_expr_ctxs, state));
    return _open_native_writer(state);
}

Status PaimonTableSink::send_chunk(RuntimeState* state, Chunk* chunk) {
    if (chunk == nullptr || chunk->num_rows() == 0) return Status::OK();
    RETURN_IF_CANCELLED(state);
    return _write_native_chunk(chunk);
}

Status PaimonTableSink::close(RuntimeState* state, Status exec_status) {
    if (_closed) return Status::OK();
    _closed = true;
    Expr::close(_output_expr_ctxs, state);
    if (!exec_status.ok()) {
        _abort_native_writer();
        return Status::OK();
    }
    return _finish_native_writer();
}

Status PaimonTableSink::decompose_to_pipeline(pipeline::OpFactories prev_operators, const TDataSink& thrift_sink,
                                              pipeline::PipelineBuilderContext* context) const {
    ASSIGN_OR_RETURN(const PaimonWriterImplementation writer, select_paimon_writer(thrift_sink.paimon_table_sink.writer_type));
    if (writer != PaimonWriterImplementation::CPP) {
        return Status::NotSupported("Paimon JNI writer was selected but no JNI writer implementation is installed");
    }
    if (context->data_sink_dop() != 1) {
        return Status::NotSupported("Paimon native sink requires data_sink_dop=1 until multi-writer commit coordination is enabled");
    }
    prev_operators.emplace_back(std::make_shared<pipeline::PaimonTableSinkOperatorFactory>(
            context->next_operator_id(), const_cast<PaimonTableSink*>(this)));
    context->add_pipeline(std::move(prev_operators));
    return Status::OK();
}

Status PaimonTableSink::_open_native_writer(RuntimeState* state) {
#ifndef ENABLE_PAIMON_CPP
    return Status::NotSupported("Paimon native writer requires ENABLE_PAIMON_CPP");
#else
    ASSIGN_OR_RETURN(const PaimonWriterImplementation writer, select_paimon_writer(_sink.writer_type));
    if (writer != PaimonWriterImplementation::CPP) {
        return Status::NotSupported("Paimon JNI writer was selected but no JNI writer implementation is installed");
    }
    if (_sink.table_location.empty()) return Status::InvalidArgument("Paimon sink requires table location");
    if (_sink.__isset.bucket_count && _sink.bucket_count > 0) {
        return Status::NotSupported("Paimon native sink requires bucket-aware exchange for fixed-bucket tables");
    }
    if (!_sink.partition_column_names.empty()) {
        return Status::NotSupported("Paimon native sink requires partition-aware exchange for partitioned tables");
    }
    auto* tuple = state->desc_tbl().get_tuple_descriptor(_sink.tuple_id);
    if (tuple == nullptr) return Status::InternalError("Paimon sink tuple descriptor is missing");
    std::vector<std::shared_ptr<arrow::Field>> fields;
    for (const auto* slot : tuple->slots()) {
        if (slot->id() == _row_kind_slot_id) continue;
        std::shared_ptr<arrow::Field> field;
        RETURN_IF_ERROR(convert_to_arrow_field(slot->type(), slot->col_name(), slot->is_nullable(), &field));
        fields.emplace_back(std::move(field));
        _data_types.emplace_back(&slot->type());
        _data_slot_ids.emplace_back(slot->id());
    }
    if (_data_slot_ids.empty()) return Status::InvalidArgument("Paimon sink has no table data columns");
    _arrow_schema = arrow::schema(std::move(fields));
    _paimon_memory_pool = std::make_shared<PaimonMemoryPool>(state->instance_mem_tracker_ptr());
    auto filesystem = std::make_shared<paimon_cpp::PaimonStarRocksFileSystem>(
            _sink.table_options, _sink.__isset.cloud_configuration ? &_sink.cloud_configuration : nullptr, state);
    paimon::WriteContextBuilder builder(_sink.table_location, _sink.commit_user);
    builder.SetOptions(_sink.table_options).WithMemoryPool(_paimon_memory_pool).WithFileSystem(std::move(filesystem));
    auto context = builder.Finish();
    if (!context.ok()) return paimon_cpp::from_paimon_status(context.status(), "build Paimon write context");
    auto writer = paimon::FileStoreWrite::Create(std::move(context).value());
    if (!writer.ok()) return paimon_cpp::from_paimon_status(writer.status(), "create Paimon FileStoreWrite");
    _native_writer = std::move(writer).value();
    _native_opened = true;
    return Status::OK();
#endif
}

Status PaimonTableSink::_write_native_chunk(Chunk* chunk) {
#ifndef ENABLE_PAIMON_CPP
    return Status::NotSupported("Paimon native writer requires ENABLE_PAIMON_CPP");
#else
    if (_native_writer == nullptr) return Status::InternalError("Paimon FileStoreWrite is not initialized");
    std::shared_ptr<arrow::RecordBatch> arrow_batch;
    RETURN_IF_ERROR(convert_chunk_to_arrow_batch(chunk, _data_types, _data_slot_ids, _arrow_schema,
                                                 arrow::default_memory_pool(), &arrow_batch));
    ArrowArray array{};
    ArrowSchema schema{};
    auto exported = arrow::ExportRecordBatch(*arrow_batch, &array, &schema);
    if (!exported.ok()) return Status::InternalError("Failed to export Arrow C Data batch: " + exported.ToString());
    std::vector<paimon::RecordBatch::RowKind> row_kinds;
    row_kinds.reserve(chunk->num_rows());
    if (_row_kind_slot_id >= 0) {
        const ColumnPtr& column = chunk->get_column_by_slot_id(_row_kind_slot_id);
        for (size_t row = 0; row < chunk->num_rows(); ++row) {
            Datum datum = column->get(row);
            if (datum.is_null()) return Status::InvalidArgument("Paimon hidden row-kind cannot be NULL");
            switch (datum.get_int8()) {
            case 0: row_kinds.emplace_back(paimon::RecordBatch::RowKind::INSERT); break;
            case 1: row_kinds.emplace_back(paimon::RecordBatch::RowKind::UPDATE_BEFORE); break;
            case 2: row_kinds.emplace_back(paimon::RecordBatch::RowKind::UPDATE_AFTER); break;
            case 3: row_kinds.emplace_back(paimon::RecordBatch::RowKind::DELETE); break;
            default: return Status::InvalidArgument("Paimon hidden row-kind is outside [0, 3]");
            }
        }
    } else {
        const auto kind = _write_mode == TPaimonWriteMode::DELETE ? paimon::RecordBatch::RowKind::DELETE
                                                                   : paimon::RecordBatch::RowKind::INSERT;
        row_kinds.assign(chunk->num_rows(), kind);
    }
    paimon::RecordBatchBuilder builder(&array);
    builder.SetRowKinds(row_kinds);
    auto batch = builder.Finish();
    if (schema.release != nullptr) schema.release(&schema);
    if (!batch.ok()) return paimon_cpp::from_paimon_status(batch.status(), "build Paimon record batch");
    return paimon_cpp::from_paimon_status(_native_writer->Write(std::move(batch).value()), "write Paimon batch");
#endif
}

Status PaimonTableSink::_finish_native_writer() {
#ifndef ENABLE_PAIMON_CPP
    return Status::NotSupported("Paimon native writer requires ENABLE_PAIMON_CPP");
#else
    if (!_native_opened || _native_writer == nullptr) return Status::OK();
    auto messages = _native_writer->PrepareCommit();
    if (!messages.ok()) {
        _abort_native_writer();
        return paimon_cpp::from_paimon_status(messages.status(), "prepare Paimon commit");
    }
    auto serialized = paimon::CommitMessage::SerializeList(messages.value(), _paimon_memory_pool);
    if (!serialized.ok()) {
        _abort_native_writer();
        return paimon_cpp::from_paimon_status(serialized.status(), "serialize Paimon commit");
    }
    const std::string& payload = serialized.value();
    if (payload.empty()) {
        paimon::Status close_status = _native_writer->Close();
        _native_writer.reset();
        _native_opened = false;
        return paimon_cpp::from_paimon_status(close_status, "close empty Paimon writer");
    }
    SHA256Digest digest;
    digest.update(payload.data(), payload.size());
    digest.digest();
    const int32_t count = std::max<size_t>(1, (payload.size() + kPaimonCommitPayloadBytes - 1) / kPaimonCommitPayloadBytes);
    const int64_t sequence = _commit_sequence.fetch_add(1);
    for (int32_t index = 0; index < count; ++index) {
        const size_t offset = static_cast<size_t>(index) * kPaimonCommitPayloadBytes;
        TPaimonCommitInfo info;
        info.__set_serializer_version(paimon::CommitMessage::CurrentVersion());
        info.__set_payload(payload.substr(offset, std::min(kPaimonCommitPayloadBytes, payload.size() - offset)));
        info.__set_writer_id(print_id(_runtime_state->fragment_instance_id()));
        info.__set_sequence(sequence);
        info.__set_checksum(digest.hex());
        info.__set_chunk_index(index);
        info.__set_chunk_count(count);
        TSinkCommitInfo sink_info;
        sink_info.__set_paimon_commit_info(std::move(info));
        _runtime_state->add_sink_commit_info(sink_info);
    }
    paimon::Status close_status = _native_writer->Close();
    _native_writer.reset();
    _native_opened = false;
    return paimon_cpp::from_paimon_status(close_status, "close Paimon writer");
#endif
}

void PaimonTableSink::_abort_native_writer() {
#ifdef ENABLE_PAIMON_CPP
    if (_native_writer != nullptr) (void)_native_writer->Close();
    _native_writer.reset();
#endif
    _native_opened = false;
}

} // namespace starrocks
