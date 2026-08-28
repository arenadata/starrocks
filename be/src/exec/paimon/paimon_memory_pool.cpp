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

#include "exec/paimon/paimon_memory_pool.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <limits>

#include "runtime/current_thread.h"
#include "runtime/mem_tracker.h"

namespace starrocks {
namespace {

constexpr size_t kDefaultAlignment = 64;

} // namespace

PaimonMemoryPool::PaimonMemoryPool(std::shared_ptr<MemTracker> mem_tracker) : _mem_tracker(std::move(mem_tracker)) {}

size_t PaimonMemoryPool::_normalize_alignment(uint64_t alignment) {
    if (alignment > std::numeric_limits<size_t>::max()) {
        return 0;
    }
    size_t normalized = alignment == 0 ? kDefaultAlignment : static_cast<size_t>(alignment);
    normalized = std::max(normalized, sizeof(void*));
    if ((normalized & (normalized - 1)) != 0) {
        size_t power_of_two = 1;
        while (power_of_two < normalized) {
            if (power_of_two > std::numeric_limits<size_t>::max() / 2) {
                return 0;
            }
            power_of_two <<= 1;
        }
        normalized = power_of_two;
    }
    return normalized;
}

void PaimonMemoryPool::_record_allocation(uint64_t size) {
    const uint64_t current = _current_usage.fetch_add(size, std::memory_order_relaxed) + size;
    uint64_t peak = _max_usage.load(std::memory_order_relaxed);
    while (current > peak &&
           !_max_usage.compare_exchange_weak(peak, current, std::memory_order_relaxed, std::memory_order_relaxed)) {
    }
}

void PaimonMemoryPool::_record_free(uint64_t size) {
    uint64_t current = _current_usage.load(std::memory_order_relaxed);
    for (;;) {
        const uint64_t released = std::min(current, size);
        if (_current_usage.compare_exchange_weak(current, current - released, std::memory_order_relaxed,
                                                 std::memory_order_relaxed)) {
            return;
        }
    }
}

void* PaimonMemoryPool::Malloc(uint64_t size, uint64_t alignment) {
    SCOPED_THREAD_LOCAL_MEM_TRACKER_SETTER(_mem_tracker.get());
    void* result = nullptr;
    const size_t normalized_alignment = _normalize_alignment(alignment);
    if (normalized_alignment == 0 || size > std::numeric_limits<size_t>::max() ||
        size > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
        return nullptr;
    }
    if (_mem_tracker != nullptr && _mem_tracker->try_consume(static_cast<int64_t>(size)) != nullptr) {
        return nullptr;
    }
    const size_t allocation_size = std::max<uint64_t>(size, 1);
    if (posix_memalign(&result, normalized_alignment, allocation_size) != 0) {
        if (_mem_tracker != nullptr) {
            _mem_tracker->release(static_cast<int64_t>(size));
        }
        return nullptr;
    }
    _record_allocation(size);
    return result;
}

void* PaimonMemoryPool::Realloc(void* ptr, size_t old_size, size_t new_size, uint64_t alignment) {
    if (ptr == nullptr) {
        return Malloc(new_size, alignment);
    }
    if (new_size == 0) {
        Free(ptr, old_size, alignment);
        return nullptr;
    }

    void* replacement = Malloc(new_size, alignment);
    if (replacement == nullptr) {
        return nullptr;
    }
    std::memcpy(replacement, ptr, std::min(old_size, new_size));
    Free(ptr, old_size, alignment);
    return replacement;
}

void PaimonMemoryPool::Free(void* ptr, uint64_t size) {
    Free(ptr, size, 0);
}

void PaimonMemoryPool::Free(void* ptr, uint64_t size, uint64_t /*alignment*/) {
    if (ptr == nullptr) {
        return;
    }
    SCOPED_THREAD_LOCAL_MEM_TRACKER_SETTER(_mem_tracker.get());
    std::free(ptr);
    if (_mem_tracker != nullptr) {
        _mem_tracker->release(static_cast<int64_t>(size));
    }
    _record_free(size);
}

} // namespace starrocks
