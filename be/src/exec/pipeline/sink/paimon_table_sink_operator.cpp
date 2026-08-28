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

#include "exec/pipeline/sink/paimon_table_sink_operator.h"

#include "runtime/paimon_table_sink.h"

namespace starrocks::pipeline {

Status PaimonTableSinkOperator::prepare(RuntimeState* state) {
    return Operator::prepare(state);
}

Status PaimonTableSinkOperator::set_finishing(RuntimeState* state) {
    if (_finished) return Status::OK();
    _finished = true;
    return _sink->close(state, Status::OK());
}

Status PaimonTableSinkOperator::set_cancelled(RuntimeState* state) {
    _finished = true;
    return _sink->close(state, Status::Cancelled("Paimon sink pipeline cancelled"));
}

StatusOr<ChunkPtr> PaimonTableSinkOperator::pull_chunk(RuntimeState*) {
    return Status::NotSupported("Paimon table sink cannot produce chunks");
}

Status PaimonTableSinkOperator::push_chunk(RuntimeState* state, const ChunkPtr& chunk) {
    return _sink->send_chunk(state, chunk.get());
}

OperatorPtr PaimonTableSinkOperatorFactory::create(int32_t, int32_t driver_sequence) {
    return std::make_shared<PaimonTableSinkOperator>(this, _id, driver_sequence, _sink);
}

} // namespace starrocks::pipeline
