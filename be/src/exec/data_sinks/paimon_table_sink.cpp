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

#include "exec/data_sinks/paimon_table_sink.h"

#include "common/runtime_profile.h"
#include "exec/pipeline/fragment_context.h"
#include "exec/pipeline/pipeline_builder.h"
#include "exprs/expr.h"
#include "exprs/expr_executor.h"
#include "exprs/expr_factory.h"
#include "runtime/runtime_state.h"
#include "runtime/service_contexts.h"

namespace starrocks {

PaimonTableSink::PaimonTableSink(ObjectPool* pool, const std::vector<TExpr>& t_exprs)
        : _pool(pool), _t_output_expr(t_exprs) {}

PaimonTableSink::~PaimonTableSink() = default;

Status PaimonTableSink::init(const TDataSink& thrift_sink, RuntimeState* state) {
    RETURN_IF_ERROR(DataSink::init(thrift_sink, state));
    RETURN_IF_ERROR(prepare(state));
    RETURN_IF_ERROR(open(state));
    return Status::OK();
}

Status PaimonTableSink::prepare(RuntimeState* state) {
    RETURN_IF_ERROR(DataSink::prepare(state));
    RETURN_IF_ERROR(ExprExecutor::prepare(_output_expr_ctxs, state));
    std::stringstream title;
    title << "PaimonTableSink (frag_id=" << state->fragment_instance_id() << ")";
    _profile = _pool->add(new RuntimeProfile(title.str()));
    return Status::OK();
}

Status PaimonTableSink::open(RuntimeState* state) {
    RETURN_IF_ERROR(ExprExecutor::open(_output_expr_ctxs, state));
    return Status::OK();
}

Status PaimonTableSink::send_chunk(RuntimeState* state, Chunk* chunk) {
    return Status::OK();
}

Status PaimonTableSink::close(RuntimeState* state, const Status& exec_status) {
    ExprExecutor::close(_output_expr_ctxs, state);
    return Status::OK();
}

Status PaimonTableSink::decompose_to_pipeline(pipeline::OpFactories prev_operators, const TDataSink& thrift_sink,
                                              pipeline::PipelineBuilderContext* context) const {
    auto runtime_state = context->runtime_state();
    auto fragment_ctx = context->fragment_context();
    auto output_exprs = this->get_output_expr();
    const auto& t_paimon_sink = thrift_sink.paimon_table_sink;
    const auto num_data_columns = t_paimon_sink.data_column_names.size();
    std::vector<TExpr> data_exprs(output_exprs.begin(), output_exprs.begin() + num_data_columns);
    std::vector<TExpr> partition_exprs(output_exprs.begin() + num_data_columns, output_exprs.end());

    auto sink_ctx = std::make_shared<connector::PaimonChunkSinkContext>();
    sink_ctx->path = t_paimon_sink.staging_dir;
    sink_ctx->cloud_conf = t_paimon_sink.cloud_configuration;
    sink_ctx->data_column_names = t_paimon_sink.data_column_names;
    sink_ctx->partition_column_names = t_paimon_sink.partition_column_names;
    sink_ctx->data_column_evaluators = ColumnExprEvaluator::from_exprs(data_exprs, runtime_state);
    sink_ctx->partition_column_evaluators = ColumnExprEvaluator::from_exprs(partition_exprs, runtime_state);
    auto* query_execution_services = runtime_state->query_execution_services();
    sink_ctx->executor = query_execution_services->execution->pipeline_sink_io_pool;
    sink_ctx->format = t_paimon_sink.file_format;
    sink_ctx->compression_type = t_paimon_sink.compression_type;
    if (t_paimon_sink.__isset.target_max_file_size) {
        sink_ctx->max_file_size = t_paimon_sink.target_max_file_size;
    }
    sink_ctx->fragment_context = fragment_ctx;

    auto sink_provider = std::make_unique<connector::PaimonChunkSinkProvider>();
    auto op = std::make_shared<pipeline::ConnectorSinkOperatorFactory>(
            context->next_operator_id(), std::move(sink_provider), sink_ctx, fragment_ctx);

    size_t sink_dop = context->data_sink_dop();
    if (t_paimon_sink.partition_column_names.empty() || t_paimon_sink.is_static_partition_sink) {
        auto ops = context->maybe_interpolate_local_passthrough_exchange(
                runtime_state, pipeline::Operator::s_pseudo_plan_node_id_for_final_sink, prev_operators, sink_dop,
                pipeline::LocalExchanger::PassThroughType::SCALE);
        ops.emplace_back(std::move(op));
        context->add_pipeline(ops);
    } else {
        std::vector<ExprContext*> partition_expr_ctxs;
        RETURN_IF_ERROR(ExprFactory::create_expr_trees(runtime_state->obj_pool(), partition_exprs, &partition_expr_ctxs,
                                                       runtime_state));
        auto ops = context->interpolate_local_key_partition_exchange(
                runtime_state, pipeline::Operator::s_pseudo_plan_node_id_for_final_sink, prev_operators,
                partition_expr_ctxs, sink_dop);
        ops.emplace_back(std::move(op));
        context->add_pipeline(ops);
    }

    return Status::OK();
}

} // namespace starrocks
