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

#include "exec/pipeline/operator.h"

namespace starrocks {
class PaimonTableSink;

namespace pipeline {
class PaimonTableSinkOperator final : public Operator {
public:
    PaimonTableSinkOperator(OperatorFactory* factory, int32_t id, int32_t driver_sequence, PaimonTableSink* sink)
            : Operator(factory, id, "paimon_table_sink", Operator::s_pseudo_plan_node_id_for_final_sink, false,
                       driver_sequence),
              _sink(sink) {}

    Status prepare(RuntimeState* state) override;
    bool has_output() const override { return false; }
    bool need_input() const override { return !_finished; }
    bool is_finished() const override { return _finished; }
    Status set_finishing(RuntimeState* state) override;
    Status set_cancelled(RuntimeState* state) override;
    StatusOr<ChunkPtr> pull_chunk(RuntimeState* state) override;
    Status push_chunk(RuntimeState* state, const ChunkPtr& chunk) override;

private:
    PaimonTableSink* _sink;
    bool _finished = false;
};

class PaimonTableSinkOperatorFactory final : public OperatorFactory {
public:
    PaimonTableSinkOperatorFactory(int32_t id, PaimonTableSink* sink)
            : OperatorFactory(id, "paimon_table_sink", Operator::s_pseudo_plan_node_id_for_final_sink), _sink(sink) {}

    OperatorPtr create(int32_t degree_of_parallelism, int32_t driver_sequence) override;

private:
    PaimonTableSink* _sink;
};
} // namespace pipeline
} // namespace starrocks
