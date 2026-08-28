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

#include "exec/paimon/paimon_starrocks_file_system.h"

#include <algorithm>
#include <charconv>
#include <cctype>
#include <limits>
#include <type_traits>
#include <utility>

#include "exec/hdfs_scanner.h"
#include "fs/fs.h"
#include "runtime/runtime_state.h"
#include "util/time.h"

namespace starrocks::paimon_cpp {
namespace {

constexpr std::string_view kRetryCountOption = "paimon.starrocks.fs.max-retries";
constexpr std::string_view kRetryDelayOption = "paimon.starrocks.fs.retry-delay-ms";
constexpr std::string_view kCancelledMarker = "[StarRocksCancelled] ";
constexpr const char* kStatusDetailType = "starrocks.paimon.status";

class StarRocksStatusDetail final : public paimon::StatusDetail {
public:
    const char* type_id() const override { return kStatusDetailType; }
    std::string ToString() const override { return "StarRocks query cancellation"; }
};

bool is_cancelled(const paimon::Status& status) {
    return (status.detail() != nullptr && std::string_view(status.detail()->type_id()) == kStatusDetailType) ||
           status.message().find(kCancelledMarker) != std::string::npos;
}

paimon::Result<int64_t> checked_add(int64_t base, int64_t offset) {
    if ((offset > 0 && base > std::numeric_limits<int64_t>::max() - offset) ||
        (offset < 0 && base < std::numeric_limits<int64_t>::min() - offset)) {
        return paimon::Status::Invalid("stream seek position overflow");
    }
    return base + offset;
}

std::string ascii_lower(std::string_view value) {
    std::string result(value);
    std::transform(result.begin(), result.end(), result.begin(),
                   [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return result;
}

bool is_sensitive_key(std::string_view key) {
    const std::string normalized = ascii_lower(key);
    return normalized.find("secret") != std::string::npos || normalized.find("password") != std::string::npos ||
           normalized.find("passwd") != std::string::npos || normalized.find("token") != std::string::npos ||
           normalized.find("credential") != std::string::npos || normalized.find("access_key") != std::string::npos ||
           normalized.find("accesskey") != std::string::npos || normalized == "sig" ||
           normalized.find("signature") != std::string::npos;
}

bool is_key_delimiter(char c) {
    return c == '?' || c == '&' || c == ';' || c == ',' || std::isspace(static_cast<unsigned char>(c));
}

bool is_value_delimiter(char c) {
    return c == '&' || c == ';' || c == ',' || std::isspace(static_cast<unsigned char>(c));
}

std::string join_path(const std::string& parent, std::string_view child) {
    if (parent.empty() || parent.back() == '/') {
        return parent + std::string(child);
    }
    return parent + "/" + std::string(child);
}

std::string parent_path(const std::string& path) {
    const size_t scheme = path.find("://");
    const size_t path_start = scheme == std::string::npos ? 0 : path.find('/', scheme + 3);
    if (path_start == std::string::npos) {
        return "";
    }
    size_t end = path.size();
    while (end > path_start + 1 && path[end - 1] == '/') {
        --end;
    }
    const size_t slash = path.rfind('/', end - 1);
    if (slash == std::string::npos || (scheme != std::string::npos && slash < path_start)) {
        return "";
    }
    if (slash == 0) {
        return "/";
    }
    return path.substr(0, slash);
}

struct ParsedPath {
    std::string scheme;
    std::string authority;
    std::string normalized;
};

ParsedPath parse_path(const std::string& path) {
    ParsedPath parsed;
    parsed.normalized = path;
    const size_t separator = path.find("://");
    if (separator == std::string::npos) {
        const size_t single_slash_separator = path.find(":/");
        if (single_slash_separator != std::string::npos) {
            parsed.scheme = ascii_lower(std::string_view(path).substr(0, single_slash_separator));
            if (parsed.scheme == "file") {
                parsed.normalized = path.substr(single_slash_separator + 1);
            }
            return parsed;
        }
        parsed.scheme = "file";
        return parsed;
    }

    parsed.scheme = ascii_lower(std::string_view(path).substr(0, separator));
    const size_t authority_start = separator + 3;
    const size_t slash = path.find('/', authority_start);
    if (slash == std::string::npos) {
        parsed.authority = path.substr(authority_start);
    } else {
        parsed.authority = path.substr(authority_start, slash - authority_start);
    }

    if (parsed.scheme == "file") {
        if (slash == std::string::npos) {
            parsed.normalized.clear();
        } else if (parsed.authority.empty() || parsed.authority == "localhost") {
            parsed.normalized = path.substr(slash);
        } else {
            parsed.normalized = "//" + parsed.authority + path.substr(slash);
        }
    }
    return parsed;
}

std::string cache_key(const ParsedPath& path) {
    if (path.scheme == "file") {
        return "file";
    }
    return path.scheme + "://" + path.authority;
}

int parse_bounded_integer(const std::map<std::string, std::string>& options, std::string_view key, int default_value,
                          int max_value) {
    auto it = options.find(std::string(key));
    if (it == options.end()) {
        return default_value;
    }
    int value = 0;
    const char* begin = it->second.data();
    const char* end = begin + it->second.size();
    auto result = std::from_chars(begin, end, value);
    if (result.ec != std::errc() || result.ptr != end || value < 0) {
        return default_value;
    }
    return std::min(value, max_value);
}

bool retryable(const Status& status) {
    return status.is_io_error() || status.is_service_unavailable() || status.is_time_out() || status.is_eagain();
}

const Status& operation_status(const Status& status) {
    return status;
}

template <typename T>
const Status& operation_status(const StatusOr<T>& result) {
    return result.status();
}

template <typename Fn>
auto retry_operation(RuntimeState* state, int max_retries, int retry_delay_ms, std::string_view operation, Fn&& fn)
        -> std::invoke_result_t<Fn> {
    using Result = std::invoke_result_t<Fn>;
    for (int attempt = 0;; ++attempt) {
        if (state != nullptr && state->is_cancelled()) {
            return Result(Status::Cancelled(std::string(operation) + " cancelled"));
        }
        Result result = fn();
        const Status& status = operation_status(result);
        if (status.ok() || attempt >= max_retries || !retryable(status)) {
            return result;
        }
        if (retry_delay_ms > 0) {
            SleepForMs(retry_delay_ms);
        }
    }
}

std::unordered_map<std::string, std::string> build_fs_options(
        const std::map<std::string, std::string>& options) {
    std::unordered_map<std::string, std::string> result(options.begin(), options.end());
    auto copy_if_absent = [&](std::string_view from, std::string_view to) {
        auto from_it = options.find(std::string(from));
        if (from_it != options.end() && !from_it->second.empty() && !result.contains(std::string(to))) {
            result.emplace(to, from_it->second);
        }
    };
    copy_if_absent("AWS_ACCESS_KEY", FSOptions::FS_S3_ACCESS_KEY);
    copy_if_absent("AWS_SECRET_KEY", FSOptions::FS_S3_SECRET_KEY);
    copy_if_absent("AWS_ENDPOINT", FSOptions::FS_S3_ENDPOINT);
    copy_if_absent("AWS_REGION", FSOptions::FS_S3_ENDPOINT_REGION);
    copy_if_absent("use_path_style", FSOptions::FS_S3_PATH_STYLE_ACCESS);
    result.erase(std::string(kRetryCountOption));
    result.erase(std::string(kRetryDelayOption));
    return result;
}

class StarRocksInputStream final : public paimon::InputStream {
public:
    StarRocksInputStream(std::unique_ptr<RandomAccessFile> file, std::string path, RuntimeState* state,
                         HdfsScanStats* stats, int max_retries, int retry_delay_ms)
            : _file(std::move(file)),
              _path(std::move(path)),
              _state(state),
              _stats(stats),
              _max_retries(max_retries),
              _retry_delay_ms(retry_delay_ms) {}

    ~StarRocksInputStream() override { (void)Close(); }

    paimon::Status Seek(int64_t offset, paimon::SeekOrigin origin) override {
        if (_file == nullptr) {
            return paimon::Status::IOError("seek on closed stream");
        }
        int64_t base = 0;
        switch (origin) {
        case paimon::FS_SEEK_SET:
            break;
        case paimon::FS_SEEK_CUR:
            base = _position;
            break;
        case paimon::FS_SEEK_END: {
            auto length = Length();
            if (!length.ok()) {
                return length.status();
            }
            if (length.value() > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
                return paimon::Status::Invalid("stream length exceeds int64");
            }
            base = static_cast<int64_t>(length.value());
            break;
        }
        default:
            return paimon::Status::Invalid("unknown seek origin");
        }
        auto target = checked_add(base, offset);
        if (!target.ok()) {
            return target.status();
        }
        if (target.value() < 0) {
            return paimon::Status::Invalid("negative seek position");
        }
        _position = target.value();
        return paimon::Status::OK();
    }

    paimon::Result<int64_t> GetPos() const override { return _position; }

    paimon::Result<int32_t> Read(char* buffer, uint32_t size) override {
        auto result = Read(buffer, size, static_cast<uint64_t>(_position));
        if (result.ok()) {
            _position += result.value();
        }
        return result;
    }

    paimon::Result<int32_t> Read(char* buffer, uint32_t size, uint64_t offset) override {
        if (_file == nullptr) {
            return paimon::Status::IOError("read on closed stream");
        }
        if (offset > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())) {
            return paimon::Status::Invalid("read offset exceeds int64");
        }
        const int64_t requested = std::min<uint64_t>(size, std::numeric_limits<int32_t>::max());
        const int64_t start_ns = MonotonicNanos();
        auto read_result = retry_operation(_state, _max_retries, _retry_delay_ms, "paimon file read", [&]() {
            return _file->read_at(static_cast<int64_t>(offset), buffer, requested);
        });
        if (_stats != nullptr) {
            _stats->io_ns += MonotonicNanos() - start_ns;
            ++_stats->io_count;
        }
        if (!read_result.ok()) {
            return to_paimon_status(read_result.status(), "read " + redact_sensitive(_path));
        }
        if (_stats != nullptr) {
            _stats->bytes_read += read_result.value();
        }
        return static_cast<int32_t>(read_result.value());
    }

    void ReadAsync(char* buffer, uint32_t size, uint64_t offset,
                   std::function<void(paimon::Status)>&& callback) override {
        auto result = Read(buffer, size, offset);
        callback(result.ok() ? paimon::Status::OK() : result.status());
    }

    paimon::Result<std::string> GetUri() const override { return _path; }

    paimon::Result<uint64_t> Length() const override {
        if (_file == nullptr) {
            return paimon::Status::IOError("length on closed stream");
        }
        auto result = retry_operation(_state, _max_retries, _retry_delay_ms, "paimon file length",
                                      [&]() { return _file->get_size(); });
        if (!result.ok()) {
            return to_paimon_status(result.status(), "stat " + redact_sensitive(_path));
        }
        if (result.value() < 0) {
            return paimon::Status::IOError("negative file length");
        }
        return static_cast<uint64_t>(result.value());
    }

    paimon::Status Close() override {
        _file.reset();
        return paimon::Status::OK();
    }

private:
    std::unique_ptr<RandomAccessFile> _file;
    std::string _path;
    RuntimeState* _state;
    HdfsScanStats* _stats;
    int _max_retries;
    int _retry_delay_ms;
    int64_t _position = 0;
};

class StarRocksOutputStream final : public paimon::OutputStream {
public:
    StarRocksOutputStream(std::unique_ptr<WritableFile> file, std::string path, RuntimeState* state)
            : _file(std::move(file)), _path(std::move(path)), _state(state) {}

    ~StarRocksOutputStream() override { (void)Close(); }

    paimon::Result<int32_t> Write(const char* buffer, uint32_t size) override {
        if (_file == nullptr) {
            return paimon::Status::IOError("write on closed stream");
        }
        if (_state != nullptr && _state->is_cancelled()) {
            return to_paimon_status(Status::Cancelled("paimon file write cancelled"));
        }
        if (size > static_cast<uint32_t>(std::numeric_limits<int32_t>::max())) {
            return paimon::Status::Invalid("single write exceeds int32");
        }
        Status status = _file->append(Slice(buffer, size));
        if (!status.ok()) {
            return to_paimon_status(status, "write " + redact_sensitive(_path));
        }
        return static_cast<int32_t>(size);
    }

    paimon::Status Flush() override {
        if (_file == nullptr) {
            return paimon::Status::IOError("flush on closed stream");
        }
        return to_paimon_status(_file->flush(WritableFile::FLUSH_SYNC), "flush " + redact_sensitive(_path));
    }

    paimon::Result<int64_t> GetPos() const override {
        return _file == nullptr ? paimon::Result<int64_t>(paimon::Status::IOError("position on closed stream"))
                                : paimon::Result<int64_t>(static_cast<int64_t>(_file->size()));
    }

    paimon::Result<std::string> GetUri() const override { return _path; }

    paimon::Status Close() override {
        if (_file == nullptr) {
            return paimon::Status::OK();
        }
        Status status = _file->close();
        _file.reset();
        return to_paimon_status(status, "close " + redact_sensitive(_path));
    }

private:
    std::unique_ptr<WritableFile> _file;
    std::string _path;
    RuntimeState* _state;
};

class BasicFileStatus final : public paimon::BasicFileStatus {
public:
    BasicFileStatus(std::string path, bool is_directory) : _path(std::move(path)), _is_directory(is_directory) {}

    bool IsDir() const override { return _is_directory; }
    std::string GetPath() const override { return _path; }

private:
    std::string _path;
    bool _is_directory;
};

class FileStatus final : public paimon::FileStatus {
public:
    FileStatus(std::string path, bool is_directory, uint64_t length, int64_t modification_time_ms)
            : _path(std::move(path)),
              _is_directory(is_directory),
              _length(length),
              _modification_time_ms(modification_time_ms) {}

    uint64_t GetLen() const override { return _length; }
    bool IsDir() const override { return _is_directory; }
    std::string GetPath() const override { return _path; }
    int64_t GetModificationTime() const override { return _modification_time_ms; }

private:
    std::string _path;
    bool _is_directory;
    uint64_t _length;
    int64_t _modification_time_ms;
};

} // namespace

std::string redact_sensitive(std::string_view message) {
    std::string result(message);

    const size_t scheme = result.find("://");
    if (scheme != std::string::npos) {
        const size_t authority_end = result.find_first_of("/?#", scheme + 3);
        const size_t at = result.find('@', scheme + 3);
        if (at != std::string::npos && (authority_end == std::string::npos || at < authority_end)) {
            result.replace(scheme + 3, at - (scheme + 3), "<redacted>");
        }
    }

    size_t equals = 0;
    while ((equals = result.find('=', equals)) != std::string::npos) {
        size_t key_begin = equals;
        while (key_begin > 0 && !is_key_delimiter(result[key_begin - 1])) {
            --key_begin;
        }
        if (!is_sensitive_key(std::string_view(result).substr(key_begin, equals - key_begin))) {
            ++equals;
            continue;
        }
        size_t value_end = equals + 1;
        while (value_end < result.size() && !is_value_delimiter(result[value_end])) {
            ++value_end;
        }
        result.replace(equals + 1, value_end - equals - 1, "<redacted>");
        equals += sizeof("<redacted>");
    }
    return result;
}

paimon::Status to_paimon_status(const Status& status, std::string_view operation) {
    if (status.ok()) {
        return paimon::Status::OK();
    }
    std::string message;
    if (!operation.empty()) {
        message.append(operation).append(": ");
    }
    message.append(status.message());
    message = redact_sensitive(message);

    if (status.is_not_found()) {
        return paimon::Status::NotExist(message);
    }
    if (status.is_already_exist()) {
        return paimon::Status::Exist(message);
    }
    if (status.is_invalid_argument() || status.is_corruption() || status.is_data_quality_error()) {
        return paimon::Status::Invalid(message);
    }
    if (status.is_cancelled()) {
        return paimon::Status(paimon::StatusCode::IOError, std::string(kCancelledMarker) + message,
                              std::make_shared<StarRocksStatusDetail>());
    }
    if (status.is_not_supported()) {
        return paimon::Status::NotImplemented(message);
    }
    if (status.is_mem_limit_exceeded() || status.code() == TStatusCode::MEM_ALLOC_FAILED ||
        status.code() == TStatusCode::BUFFER_ALLOCATION_FAILED) {
        return paimon::Status::OutOfMemory(message);
    }
    return paimon::Status::IOError(message);
}

Status from_paimon_status(const paimon::Status& status, std::string_view operation) {
    if (status.ok()) {
        return Status::OK();
    }
    std::string status_message = status.message();
    const bool cancelled = is_cancelled(status);
    if (const size_t marker = status_message.find(kCancelledMarker); marker != std::string::npos) {
        status_message.erase(marker, kCancelledMarker.size());
    }
    std::string message;
    if (!operation.empty()) {
        message.append(operation).append(": ");
    }
    message.append(status_message);
    message = redact_sensitive(message);

    if (status.IsOutOfMemory() || status.IsCapacityError()) {
        return Status::MemoryLimitExceeded(message);
    }
    if (cancelled) {
        return Status::Cancelled(message);
    }
    if (status.IsNotImplemented()) {
        return Status::NotSupported(message);
    }
    if (status.IsNotExist()) {
        return Status::NotFound(message);
    }
    if (status.IsExist()) {
        return Status::AlreadyExist(message);
    }
    if (status.IsInvalid() || status.IsSerializationError() || status.IsTypeError() || status.IsIndexError() ||
        status.IsKeyError()) {
        return Status::InvalidArgument(message);
    }
    return Status::IOError(message);
}

PaimonStarRocksFileSystem::PaimonStarRocksFileSystem(std::map<std::string, std::string> options,
                                                     const TCloudConfiguration* cloud_configuration,
                                                     RuntimeState* runtime_state, HdfsScanStats* stats)
        : _options(std::move(options)),
          _fs_options(build_fs_options(_options)),
          _runtime_state(runtime_state),
          _stats(stats),
          _max_retries(parse_bounded_integer(_options, kRetryCountOption, 2, 10)),
          _retry_delay_ms(parse_bounded_integer(_options, kRetryDelayOption, 50, 10'000)) {
    if (cloud_configuration != nullptr) {
        _cloud_configuration = *cloud_configuration;
        _has_cloud_configuration = true;
    }
}

Status PaimonStarRocksFileSystem::_check_cancelled(std::string_view operation) const {
    if (_runtime_state != nullptr && _runtime_state->is_cancelled()) {
        return Status::Cancelled(std::string(operation) + " cancelled");
    }
    return Status::OK();
}

StatusOr<PaimonStarRocksFileSystem::ResolvedPath> PaimonStarRocksFileSystem::_resolve(
        const std::string& path) const {
    RETURN_IF_ERROR(_check_cancelled("paimon filesystem operation"));
    ParsedPath parsed = parse_path(path);
    if (parsed.normalized.empty()) {
        return Status::InvalidArgument("Paimon filesystem path is empty");
    }
    const std::string key = cache_key(parsed);
    {
        std::lock_guard lock(_fs_mutex);
        auto it = _file_systems.find(key);
        if (it != _file_systems.end()) {
            return ResolvedPath{it->second, std::move(parsed.normalized), key};
        }
    }

    FSOptions fs_options(_fs_options);
    if (_has_cloud_configuration) {
        fs_options.cloud_configuration = &_cloud_configuration;
    }
    ASSIGN_OR_RETURN(auto unique_fs,
                     ::starrocks::FileSystem::CreateUniqueFromString(parsed.normalized, fs_options));
    std::shared_ptr<::starrocks::FileSystem> fs(std::move(unique_fs));
    {
        std::lock_guard lock(_fs_mutex);
        auto [it, inserted] = _file_systems.emplace(key, fs);
        if (!inserted) {
            fs = it->second;
        }
    }
    return ResolvedPath{std::move(fs), std::move(parsed.normalized), key};
}

paimon::Result<std::unique_ptr<paimon::InputStream>> PaimonStarRocksFileSystem::Open(
        const std::string& path) const {
    auto resolved = _resolve(path);
    if (!resolved.ok()) {
        return to_paimon_status(resolved.status(), "open " + redact_sensitive(path));
    }
    auto file = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon file open",
                                [&]() { return resolved->fs->new_random_access_file(resolved->path); });
    if (!file.ok()) {
        return to_paimon_status(file.status(), "open " + redact_sensitive(path));
    }
    return std::make_unique<StarRocksInputStream>(std::move(file).value(), resolved->path, _runtime_state, _stats,
                                                  _max_retries, _retry_delay_ms);
}

paimon::Result<std::unique_ptr<paimon::OutputStream>> PaimonStarRocksFileSystem::Create(
        const std::string& path, bool overwrite) const {
    auto resolved = _resolve(path);
    if (!resolved.ok()) {
        return to_paimon_status(resolved.status(), "create " + redact_sensitive(path));
    }

    if (resolved->fs->type() != ::starrocks::FileSystem::S3) {
        const std::string parent = parent_path(resolved->path);
        if (!parent.empty()) {
            Status status = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon mkdir",
                                            [&]() { return resolved->fs->create_dir_recursive(parent); });
            if (!status.ok()) {
                return to_paimon_status(status, "mkdir " + redact_sensitive(parent));
            }
        }
    }

    WritableFileOptions write_options;
    write_options.mode = overwrite ? ::starrocks::FileSystem::CREATE_OR_OPEN_WITH_TRUNCATE
                                   : ::starrocks::FileSystem::MUST_CREATE;
    // Creation is not retried: an ambiguous remote failure followed by MUST_CREATE
    // could turn a successful first attempt into a false AlreadyExist response.
    auto file = resolved->fs->new_writable_file(write_options, resolved->path);
    if (!file.ok()) {
        return to_paimon_status(file.status(), "create " + redact_sensitive(path));
    }
    return std::make_unique<StarRocksOutputStream>(std::move(file).value(), resolved->path, _runtime_state);
}

paimon::Status PaimonStarRocksFileSystem::Mkdirs(const std::string& path) const {
    auto resolved = _resolve(path);
    if (!resolved.ok()) {
        return to_paimon_status(resolved.status(), "mkdir " + redact_sensitive(path));
    }
    Status status = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon mkdir",
                                    [&]() { return resolved->fs->create_dir_recursive(resolved->path); });
    return to_paimon_status(status, "mkdir " + redact_sensitive(path));
}

paimon::Status PaimonStarRocksFileSystem::Rename(const std::string& src, const std::string& dst) const {
    auto source = _resolve(src);
    if (!source.ok()) {
        return to_paimon_status(source.status(), "rename " + redact_sensitive(src));
    }
    auto destination = _resolve(dst);
    if (!destination.ok()) {
        return to_paimon_status(destination.status(), "rename " + redact_sensitive(dst));
    }
    if (source->cache_key != destination->cache_key) {
        return paimon::Status::Invalid("cross-filesystem rename is not supported");
    }
    // Rename is not generally idempotent across object stores.
    Status status = source->fs->rename_file(source->path, destination->path);
    return to_paimon_status(status,
                            "rename " + redact_sensitive(src) + " to " + redact_sensitive(dst));
}

paimon::Status PaimonStarRocksFileSystem::Delete(const std::string& path, bool recursive) const {
    auto resolved = _resolve(path);
    if (!resolved.ok()) {
        return resolved.status().is_not_found() ? paimon::Status::OK()
                                                : to_paimon_status(resolved.status(), "delete " + redact_sensitive(path));
    }
    auto is_directory =
            retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon stat",
                            [&]() { return resolved->fs->is_directory(resolved->path); });
    if (!is_directory.ok()) {
        return is_directory.status().is_not_found()
                       ? paimon::Status::OK()
                       : to_paimon_status(is_directory.status(), "delete " + redact_sensitive(path));
    }
    Status status;
    if (is_directory.value()) {
        status = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon delete directory", [&]() {
            return recursive ? resolved->fs->delete_dir_recursive(resolved->path)
                             : resolved->fs->delete_dir(resolved->path);
        });
    } else {
        status = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon delete file",
                                 [&]() { return resolved->fs->delete_file(resolved->path); });
    }
    return status.is_not_found() ? paimon::Status::OK()
                                 : to_paimon_status(status, "delete " + redact_sensitive(path));
}

paimon::Result<std::unique_ptr<paimon::FileStatus>> PaimonStarRocksFileSystem::GetFileStatus(
        const std::string& path) const {
    auto resolved = _resolve(path);
    if (!resolved.ok()) {
        return to_paimon_status(resolved.status(), "stat " + redact_sensitive(path));
    }
    auto is_directory =
            retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon stat",
                            [&]() { return resolved->fs->is_directory(resolved->path); });
    if (!is_directory.ok()) {
        return to_paimon_status(is_directory.status(), "stat " + redact_sensitive(path));
    }
    if (is_directory.value()) {
        return std::make_unique<FileStatus>(resolved->path, true, 0, 0);
    }

    uint64_t length = 0;
    auto size = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon file size",
                                [&]() { return resolved->fs->get_file_size(resolved->path); });
    if (size.ok()) {
        length = size.value();
    } else if (size.status().is_not_supported()) {
        auto file = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon file open",
                                    [&]() { return resolved->fs->new_random_access_file(resolved->path); });
        if (!file.ok()) {
            return to_paimon_status(file.status(), "stat " + redact_sensitive(path));
        }
        auto stream_size = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon file size",
                                           [&]() { return file.value()->get_size(); });
        if (!stream_size.ok()) {
            return to_paimon_status(stream_size.status(), "stat " + redact_sensitive(path));
        }
        length = static_cast<uint64_t>(stream_size.value());
    } else {
        return to_paimon_status(size.status(), "stat " + redact_sensitive(path));
    }

    int64_t modification_time_ms = 0;
    auto modification_time =
            retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon modification time",
                            [&]() { return resolved->fs->get_file_modified_time(resolved->path); });
    if (modification_time.ok()) {
        modification_time_ms = static_cast<int64_t>(modification_time.value()) * 1000;
    } else if (!modification_time.status().is_not_supported()) {
        return to_paimon_status(modification_time.status(), "stat " + redact_sensitive(path));
    }
    return std::make_unique<FileStatus>(resolved->path, false, length, modification_time_ms);
}

paimon::Status PaimonStarRocksFileSystem::ListDir(
        const std::string& directory,
        std::vector<std::unique_ptr<paimon::BasicFileStatus>>* status_list) const {
    if (status_list == nullptr) {
        return paimon::Status::Invalid("status list is null");
    }
    auto resolved = _resolve(directory);
    if (!resolved.ok()) {
        return to_paimon_status(resolved.status(), "list " + redact_sensitive(directory));
    }
    std::vector<std::pair<std::string, bool>> entries;
    Status status = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon list", [&]() {
        entries.clear();
        return resolved->fs->iterate_dir2(resolved->path, [&](DirEntry entry) {
            entries.emplace_back(join_path(resolved->path, entry.name), entry.is_dir.value_or(false));
            return _runtime_state == nullptr || !_runtime_state->is_cancelled();
        });
    });
    if (!status.ok()) {
        return to_paimon_status(status, "list " + redact_sensitive(directory));
    }
    Status cancelled = _check_cancelled("paimon list");
    if (!cancelled.ok()) {
        return to_paimon_status(cancelled);
    }
    status_list->reserve(status_list->size() + entries.size());
    for (auto& [path, is_directory] : entries) {
        status_list->emplace_back(std::make_unique<BasicFileStatus>(std::move(path), is_directory));
    }
    return paimon::Status::OK();
}

paimon::Status PaimonStarRocksFileSystem::ListFileStatus(
        const std::string& path, std::vector<std::unique_ptr<paimon::FileStatus>>* status_list) const {
    if (status_list == nullptr) {
        return paimon::Status::Invalid("status list is null");
    }
    auto self = GetFileStatus(path);
    if (!self.ok()) {
        return self.status().IsNotExist() ? paimon::Status::OK() : self.status();
    }
    if (!self.value()->IsDir()) {
        status_list->emplace_back(std::move(self).value());
        return paimon::Status::OK();
    }

    auto resolved = _resolve(path);
    if (!resolved.ok()) {
        return to_paimon_status(resolved.status(), "list " + redact_sensitive(path));
    }
    struct Entry {
        std::string path;
        bool is_directory;
        uint64_t size;
        int64_t modification_time_ms;
    };
    std::vector<Entry> entries;
    Status status = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon list", [&]() {
        entries.clear();
        return resolved->fs->iterate_dir2(resolved->path, [&](DirEntry entry) {
            const bool is_directory = entry.is_dir.value_or(false);
            const int64_t entry_size = std::max<int64_t>(entry.size.value_or(0), 0);
            entries.emplace_back(Entry{join_path(resolved->path, entry.name), is_directory,
                                       is_directory ? 0 : static_cast<uint64_t>(entry_size),
                                       entry.mtime.value_or(0) * 1000});
            return _runtime_state == nullptr || !_runtime_state->is_cancelled();
        });
    });
    if (!status.ok()) {
        return to_paimon_status(status, "list " + redact_sensitive(path));
    }
    Status cancelled = _check_cancelled("paimon list");
    if (!cancelled.ok()) {
        return to_paimon_status(cancelled);
    }
    status_list->reserve(status_list->size() + entries.size());
    for (auto& entry : entries) {
        status_list->emplace_back(std::make_unique<FileStatus>(
                std::move(entry.path), entry.is_directory, entry.size, entry.modification_time_ms));
    }
    return paimon::Status::OK();
}

paimon::Result<bool> PaimonStarRocksFileSystem::Exists(const std::string& path) const {
    auto resolved = _resolve(path);
    if (!resolved.ok()) {
        return resolved.status().is_not_found() ? paimon::Result<bool>(false)
                                                : paimon::Result<bool>(
                                                          to_paimon_status(resolved.status(), "exists " + redact_sensitive(path)));
    }
    auto result = retry_operation(_runtime_state, _max_retries, _retry_delay_ms, "paimon exists",
                                  [&]() { return resolved->fs->is_directory(resolved->path); });
    if (result.ok()) {
        return true;
    }
    if (result.status().is_not_found()) {
        return false;
    }
    return to_paimon_status(result.status(), "exists " + redact_sensitive(path));
}

} // namespace starrocks::paimon_cpp
