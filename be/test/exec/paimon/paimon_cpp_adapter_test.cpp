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

#include "exec/paimon/paimon_cpp_scanner.h"

#include <gtest/gtest.h>
#include <unistd.h>

#include <filesystem>
#include <limits>

#include "exec/paimon/paimon_memory_pool.h"
#include "exec/paimon/paimon_starrocks_file_system.h"
#include "runtime/runtime_state.h"

namespace starrocks {
namespace {

class PaimonCppAdapterTest : public testing::Test {
protected:
    void SetUp() override {
        TUniqueId fragment_id;
        _state = std::make_unique<RuntimeState>(fragment_id, TQueryOptions(), TQueryGlobals(), nullptr);
        _state->init_instance_mem_tracker();
        _root = std::filesystem::temp_directory_path() /
                ("starrocks-paimon-cpp-" + std::to_string(getpid()) + "-" +
                 std::to_string(reinterpret_cast<uintptr_t>(this)));
        std::filesystem::remove_all(_root);
    }

    void TearDown() override { std::filesystem::remove_all(_root); }

    std::unique_ptr<RuntimeState> _state;
    std::filesystem::path _root;
};

TEST_F(PaimonCppAdapterTest, ValidatesStandardBase64Split) {
    std::string decoded;
    EXPECT_FALSE(validate_paimon_split_base64("", &decoded).ok());
    EXPECT_FALSE(validate_paimon_split_base64("abc", &decoded).ok());
    EXPECT_FALSE(validate_paimon_split_base64("abcd=abc", &decoded).ok());
    EXPECT_FALSE(validate_paimon_split_base64("YWJjZA$=", &decoded).ok());

    ASSERT_TRUE(validate_paimon_split_base64("YWJjZA==", &decoded).ok());
    EXPECT_EQ("abcd", decoded);
}

TEST_F(PaimonCppAdapterTest, RejectsInvalidSerializedSplit) {
    auto pool = std::make_shared<PaimonMemoryPool>(_state->instance_mem_tracker_ptr());
    auto result = deserialize_paimon_split("YWJjZA==", pool);
    ASSERT_FALSE(result.ok());
    EXPECT_NE(std::string::npos, result.status().message().find("deserialize Paimon split"));
}

TEST_F(PaimonCppAdapterTest, TracksPaimonAllocations) {
    PaimonMemoryPool pool(_state->instance_mem_tracker_ptr());
    void* allocation = pool.Malloc(128, 64);
    ASSERT_NE(nullptr, allocation);
    EXPECT_EQ(128, pool.CurrentUsage());
    EXPECT_EQ(128, pool.MaxMemoryUsage());
    pool.Free(allocation, 128, 64);
    EXPECT_EQ(0, pool.CurrentUsage());
}

TEST_F(PaimonCppAdapterTest, ImplementsLocalFileSystemOperations) {
    paimon_cpp::PaimonStarRocksFileSystem fs({}, nullptr, _state.get());
    const std::string directory = (_root / "nested").string();
    const std::string file = (_root / "nested" / "data.bin").string();
    const std::string renamed = (_root / "nested" / "renamed.bin").string();

    ASSERT_TRUE(fs.Mkdirs(directory).ok());
    auto output = fs.Create(file, false);
    ASSERT_TRUE(output.ok()) << output.status().ToString();
    ASSERT_EQ(4, output.value()->Write("data", 4).value());
    ASSERT_TRUE(output.value()->Flush().ok());
    ASSERT_TRUE(output.value()->Close().ok());

    ASSERT_TRUE(fs.Exists(file).value());
    ASSERT_TRUE(fs.Exists("file:" + file).value());
    ASSERT_TRUE(fs.Exists("file://" + file).value());
    auto status = fs.GetFileStatus(file);
    ASSERT_TRUE(status.ok()) << status.status().ToString();
    EXPECT_FALSE(status.value()->IsDir());
    EXPECT_EQ(4, status.value()->GetLen());

    std::vector<std::unique_ptr<paimon::FileStatus>> files;
    ASSERT_TRUE(fs.ListFileStatus(directory, &files).ok());
    ASSERT_EQ(1, files.size());
    EXPECT_EQ(file, files[0]->GetPath());

    ASSERT_TRUE(fs.Rename(file, renamed).ok());
    EXPECT_FALSE(fs.Exists(file).value());
    EXPECT_TRUE(fs.Exists(renamed).value());

    auto input = fs.Open(renamed);
    ASSERT_TRUE(input.ok()) << input.status().ToString();
    EXPECT_FALSE(input.value()->Seek(-1, paimon::FS_SEEK_SET).ok());
    EXPECT_FALSE(input.value()->Seek(std::numeric_limits<int64_t>::max(), paimon::FS_SEEK_END).ok());
    char buffer[4] = {};
    ASSERT_EQ(4, input.value()->Read(buffer, sizeof(buffer)).value());
    EXPECT_EQ("data", std::string(buffer, sizeof(buffer)));
    ASSERT_TRUE(input.value()->Close().ok());

    ASSERT_TRUE(fs.Delete(_root.string(), true).ok());
    EXPECT_FALSE(fs.Exists(_root.string()).value());
}

TEST_F(PaimonCppAdapterTest, PropagatesCancellation) {
    paimon_cpp::PaimonStarRocksFileSystem fs({}, nullptr, _state.get());
    _state->set_is_cancelled(true);
    auto result = fs.Exists(_root.string());
    ASSERT_FALSE(result.ok());
    EXPECT_TRUE(paimon_cpp::from_paimon_status(result.status()).is_cancelled());
}

TEST(PaimonCppStatusTest, MapsStatusAndRedactsCredentials) {
    auto not_found = paimon_cpp::to_paimon_status(Status::NotFound("missing"));
    EXPECT_TRUE(not_found.IsNotExist());
    auto out_of_memory = paimon_cpp::to_paimon_status(Status::MemoryAllocFailed("allocation failed"));
    EXPECT_TRUE(out_of_memory.IsOutOfMemory());

    auto cancelled =
            paimon_cpp::from_paimon_status(paimon_cpp::to_paimon_status(Status::Cancelled("stopped")));
    EXPECT_TRUE(cancelled.is_cancelled());

    const std::string sensitive =
            "https://user:password@example.test/file?token=top-secret&AWS_SECRET_KEY=also-secret";
    const std::string redacted = paimon_cpp::redact_sensitive(sensitive);
    EXPECT_EQ(std::string::npos, redacted.find("password"));
    EXPECT_EQ(std::string::npos, redacted.find("top-secret"));
    EXPECT_EQ(std::string::npos, redacted.find("also-secret"));
    EXPECT_NE(std::string::npos, redacted.find("<redacted>"));
}

} // namespace
} // namespace starrocks
