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

#include "exec/paimon/paimon_sink_provider.h"

#include <gtest/gtest.h>

namespace starrocks {

TEST(PaimonSinkProviderTest, AutoSelectsTheSafeJniWriter) {
    ASSERT_EQ(PaimonWriterImplementation::JNI, select_paimon_writer(TPaimonWriterType::AUTO).value());
}

TEST(PaimonSinkProviderTest, PreservesExplicitWriterSelections) {
    ASSERT_EQ(PaimonWriterImplementation::CPP, select_paimon_writer(TPaimonWriterType::CPP).value());
    ASSERT_EQ(PaimonWriterImplementation::JNI, select_paimon_writer(TPaimonWriterType::JNI).value());
}

TEST(PaimonSinkProviderTest, RejectsUnknownWriterSelection) {
    auto writer = select_paimon_writer(static_cast<TPaimonWriterType::type>(-1));
    ASSERT_FALSE(writer.ok());
    EXPECT_TRUE(writer.status().is_invalid_argument());
}

} // namespace starrocks
