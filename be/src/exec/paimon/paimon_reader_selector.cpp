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

#include "exec/paimon/paimon_reader_selector.h"

#include <fmt/format.h>

namespace starrocks {

StatusOr<PaimonReaderImplementation> select_paimon_reader(const THdfsScanRange& scan_range,
                                                          bool paimon_cpp_compiled) {
    if (scan_range.__isset.paimon_reader_type) {
        switch (scan_range.paimon_reader_type) {
        case TPaimonReaderType::PAIMON_NATIVE:
            return PaimonReaderImplementation::NATIVE;
        case TPaimonReaderType::PAIMON_JNI:
            return PaimonReaderImplementation::JNI;
        case TPaimonReaderType::PAIMON_CPP:
            if (!paimon_cpp_compiled) {
                return Status::NotSupported(
                        "Paimon C++ reader was requested, but this backend was built without ENABLE_PAIMON_CPP");
            }
            return PaimonReaderImplementation::CPP;
        default:
            return Status::InvalidArgument(
                    fmt::format("Unknown Paimon reader type: {}", static_cast<int>(scan_range.paimon_reader_type)));
        }
    }

    if (scan_range.__isset.use_paimon_jni_reader && scan_range.use_paimon_jni_reader) {
        return PaimonReaderImplementation::JNI;
    }
    return PaimonReaderImplementation::NATIVE;
}

} // namespace starrocks
