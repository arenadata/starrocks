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

#include <gtest/gtest.h>

namespace starrocks {

TEST(PaimonReaderSelectorTest, PreservesLegacySelection) {
    THdfsScanRange scan_range;
    ASSERT_EQ(PaimonReaderImplementation::NATIVE, select_paimon_reader(scan_range, false).value());

    scan_range.__set_use_paimon_jni_reader(true);
    ASSERT_EQ(PaimonReaderImplementation::JNI, select_paimon_reader(scan_range, false).value());
}

TEST(PaimonReaderSelectorTest, ExplicitSelectionOverridesLegacyFlag) {
    THdfsScanRange scan_range;
    scan_range.__set_use_paimon_jni_reader(true);
    scan_range.__set_paimon_reader_type(TPaimonReaderType::PAIMON_NATIVE);
    ASSERT_EQ(PaimonReaderImplementation::NATIVE, select_paimon_reader(scan_range, true).value());

    scan_range.__set_paimon_reader_type(TPaimonReaderType::PAIMON_JNI);
    ASSERT_EQ(PaimonReaderImplementation::JNI, select_paimon_reader(scan_range, true).value());

    scan_range.__set_paimon_reader_type(TPaimonReaderType::PAIMON_CPP);
    ASSERT_EQ(PaimonReaderImplementation::CPP, select_paimon_reader(scan_range, true).value());
}

TEST(PaimonReaderSelectorTest, RejectsForcedCppWhenNotCompiled) {
    THdfsScanRange scan_range;
    scan_range.__set_paimon_reader_type(TPaimonReaderType::PAIMON_CPP);

    auto result = select_paimon_reader(scan_range, false);
    ASSERT_FALSE(result.ok());
    EXPECT_TRUE(result.status().is_not_supported());
    EXPECT_NE(std::string::npos, result.status().message().find("ENABLE_PAIMON_CPP"));
}

TEST(PaimonReaderSelectorTest, AcceptsCppOnlyWhenCapabilityIsAdvertised) {
    THdfsScanRange scan_range;
    scan_range.__set_paimon_reader_type(TPaimonReaderType::PAIMON_CPP);

    ASSERT_EQ(PaimonReaderImplementation::CPP, select_paimon_reader(scan_range, true).value());
}

TEST(PaimonReaderSelectorTest, RejectsUnknownExplicitReaderType) {
    THdfsScanRange scan_range;
    scan_range.__set_paimon_reader_type(static_cast<TPaimonReaderType::type>(-1));

    auto result = select_paimon_reader(scan_range, true);
    ASSERT_FALSE(result.ok());
    EXPECT_TRUE(result.status().is_invalid_argument());
}

} // namespace starrocks
