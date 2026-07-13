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

#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "common/status.h"
#include "common/statusor.h"
#include "gen_cpp/PlanNodes_types.h"
#include "paimon/fs/file_system.h"

namespace starrocks {

class FileSystem;
class RuntimeState;
struct HdfsScanStats;

namespace paimon_cpp {

std::string redact_sensitive(std::string_view message);
paimon::Status to_paimon_status(const Status& status, std::string_view operation = {});
Status from_paimon_status(const paimon::Status& status, std::string_view operation = {});

class PaimonStarRocksFileSystem final : public paimon::FileSystem {
public:
    PaimonStarRocksFileSystem(std::map<std::string, std::string> options,
                             const TCloudConfiguration* cloud_configuration, RuntimeState* runtime_state,
                             HdfsScanStats* stats = nullptr);
    ~PaimonStarRocksFileSystem() override = default;

    paimon::Result<std::unique_ptr<paimon::InputStream>> Open(const std::string& path) const override;
    paimon::Result<std::unique_ptr<paimon::OutputStream>> Create(const std::string& path,
                                                                 bool overwrite) const override;
    paimon::Status Mkdirs(const std::string& path) const override;
    paimon::Status Rename(const std::string& src, const std::string& dst) const override;
    paimon::Status Delete(const std::string& path, bool recursive = true) const override;
    paimon::Result<std::unique_ptr<paimon::FileStatus>> GetFileStatus(const std::string& path) const override;
    paimon::Status ListDir(const std::string& directory,
                           std::vector<std::unique_ptr<paimon::BasicFileStatus>>* status_list) const override;
    paimon::Status ListFileStatus(const std::string& path,
                                  std::vector<std::unique_ptr<paimon::FileStatus>>* status_list) const override;
    paimon::Result<bool> Exists(const std::string& path) const override;

private:
    struct ResolvedPath {
        // Must be starrocks::FileSystem: unqualified FileSystem resolves to the
        // paimon::FileSystem injected-class-name from the base class.
        std::shared_ptr<::starrocks::FileSystem> fs;
        std::string path;
        std::string cache_key;
    };

    StatusOr<ResolvedPath> _resolve(const std::string& path) const;
    Status _check_cancelled(std::string_view operation) const;

    std::map<std::string, std::string> _options;
    std::unordered_map<std::string, std::string> _fs_options;
    TCloudConfiguration _cloud_configuration;
    bool _has_cloud_configuration = false;
    RuntimeState* _runtime_state = nullptr;
    HdfsScanStats* _stats = nullptr;
    int _max_retries = 2;
    int _retry_delay_ms = 50;

    mutable std::mutex _fs_mutex;
    mutable std::unordered_map<std::string, std::shared_ptr<::starrocks::FileSystem>> _file_systems;
};

} // namespace paimon_cpp
} // namespace starrocks
