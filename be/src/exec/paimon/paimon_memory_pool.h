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
#include <cstdint>
#include <memory>

#include "paimon/memory/memory_pool.h"

namespace starrocks {

class MemTracker;

// Routes paimon-cpp and its Arrow adaptors through StarRocks' thread-local
// allocation accounting while retaining the statistics required by Paimon.
class PaimonMemoryPool final : public paimon::MemoryPool {
public:
    explicit PaimonMemoryPool(std::shared_ptr<MemTracker> mem_tracker);
    ~PaimonMemoryPool() override = default;

    void* Malloc(uint64_t size, uint64_t alignment = 0) override;
    void* Realloc(void* ptr, size_t old_size, size_t new_size, uint64_t alignment = 0) override;
    void Free(void* ptr, uint64_t size) override;
    void Free(void* ptr, uint64_t size, uint64_t alignment) override;

    uint64_t CurrentUsage() const override { return _current_usage.load(std::memory_order_relaxed); }
    uint64_t MaxMemoryUsage() const override { return _max_usage.load(std::memory_order_relaxed); }

private:
    static size_t _normalize_alignment(uint64_t alignment);
    void _record_allocation(uint64_t size);
    void _record_free(uint64_t size);

    std::shared_ptr<MemTracker> _mem_tracker;
    std::atomic<uint64_t> _current_usage{0};
    std::atomic<uint64_t> _max_usage{0};
};

} // namespace starrocks
