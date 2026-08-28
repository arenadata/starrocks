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

#include <atomic>
#include <memory>
#include <vector>

#include "exec/data_sink.h"
#include "gen_cpp/DataSinks_types.h"

namespace arrow {
class Schema;
}
namespace paimon {
class FileStoreWrite;
class MemoryPool;
}

namespace starrocks {

class ExprContext;

// Paimon commit messages are not file descriptors: a writer must prepare a
// versioned CommitMessage and report it to RuntimeState.  Keep the sink
// separate from Hive/Iceberg so the runtime never publishes a file that has
// not been accepted by Paimon's global commit protocol.
class PaimonTableSink final : public DataSink {
public:
    PaimonTableSink(ObjectPool* pool, const std::vector<TExpr>& output_exprs);
    ~PaimonTableSink() override;

    Status init(const TDataSink& thrift_sink, RuntimeState* state) override;
    Status prepare(RuntimeState* state) override;
    Status open(RuntimeState* state) override;
    Status send_chunk(RuntimeState* state, Chunk* chunk) override;
    Status close(RuntimeState* state, Status exec_status) override;
    RuntimeProfile* profile() override { return _profile; }

    Status decompose_to_pipeline(pipeline::OpFactories prev_operators, const TDataSink& thrift_sink,
                                 pipeline::PipelineBuilderContext* context) const;

private:
    Status _open_native_writer(RuntimeState* state);
    Status _write_native_chunk(Chunk* chunk);
    Status _finish_native_writer();
    void _abort_native_writer();

    ObjectPool* _pool;
    const std::vector<TExpr>& _output_exprs;
    std::vector<ExprContext*> _output_expr_ctxs;
    // Output exprs that map to table data columns (excludes hidden row-kind).
    std::vector<ExprContext*> _data_expr_ctxs;
    RuntimeProfile* _profile = nullptr;
    TPaimonTableSink _sink;
    TPaimonWriteMode::type _write_mode = TPaimonWriteMode::APPEND;
    int32_t _row_kind_slot_id = -1;
    int32_t _row_kind_expr_idx = -1;
    std::shared_ptr<arrow::Schema> _arrow_schema;
    std::shared_ptr<paimon::MemoryPool> _paimon_memory_pool;
    std::unique_ptr<paimon::FileStoreWrite> _native_writer;
    std::atomic<int64_t> _commit_sequence{0};
    bool _native_opened = false;
};

} // namespace starrocks
