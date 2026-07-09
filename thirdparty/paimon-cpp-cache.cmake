# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# CMake Initial Cache for paimon-cpp.
# Usage: cmake -C thirdparty/paimon-cpp-cache.cmake ...

set(STARROCKS_THIRDPARTY_DIR "$ENV{TP_INSTALL_DIR}" CACHE PATH "StarRocks thirdparty install directory")
if(NOT STARROCKS_THIRDPARTY_DIR)
    message(FATAL_ERROR "TP_INSTALL_DIR environment variable must be set")
endif()

set(STARROCKS_INCLUDE_DIR "${STARROCKS_THIRDPARTY_DIR}/include" CACHE PATH "StarRocks include directory")
set(STARROCKS_LIB_DIR "${STARROCKS_THIRDPARTY_DIR}/lib" CACHE PATH "StarRocks library directory")
set(STARROCKS_LIB64_DIR "${STARROCKS_THIRDPARTY_DIR}/lib64" CACHE PATH "StarRocks lib64 directory")

# Prefer lib64 (Linux), fall back to lib (Darwin installs Arrow to lib/ and
# sync_lib64_links may symlink into lib64).
function(starrocks_resolve_tp_library out_var file_name)
    if(EXISTS "${STARROCKS_LIB64_DIR}/${file_name}")
        set(${out_var} "${STARROCKS_LIB64_DIR}/${file_name}" PARENT_SCOPE)
    elseif(EXISTS "${STARROCKS_LIB_DIR}/${file_name}")
        set(${out_var} "${STARROCKS_LIB_DIR}/${file_name}" PARENT_SCOPE)
    else()
        set(${out_var} "${STARROCKS_LIB64_DIR}/${file_name}" PARENT_SCOPE)
    endif()
endfunction()

set(CMAKE_PREFIX_PATH "${STARROCKS_THIRDPARTY_DIR};${CMAKE_PREFIX_PATH}" CACHE STRING "Search path for find_package")

starrocks_resolve_tp_library(_STARROCKS_ZLIB_LIBRARY libz.a)
set(ZLIB_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "ZLIB root directory")
set(ZLIB_LIBRARY "${_STARROCKS_ZLIB_LIBRARY}" CACHE FILEPATH "ZLIB library")
set(ZLIB_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "ZLIB include directory")

starrocks_resolve_tp_library(_STARROCKS_ZSTD_LIBRARY libzstd.a)
set(ZSTD_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "ZSTD root directory")
set(ZSTD_LIBRARY "${_STARROCKS_ZSTD_LIBRARY}" CACHE FILEPATH "ZSTD library")
set(ZSTD_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "ZSTD include directory")

starrocks_resolve_tp_library(_STARROCKS_LZ4_LIBRARY liblz4.a)
set(LZ4_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "LZ4 root directory")
set(LZ4_LIBRARY "${_STARROCKS_LZ4_LIBRARY}" CACHE FILEPATH "LZ4 library")
set(LZ4_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "LZ4 include directory")

starrocks_resolve_tp_library(_STARROCKS_SNAPPY_LIBRARY libsnappy.a)
set(SNAPPY_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "Snappy root directory")
set(SNAPPY_LIBRARY "${_STARROCKS_SNAPPY_LIBRARY}" CACHE FILEPATH "Snappy library")
set(SNAPPY_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "Snappy include directory")

# Reuse StarRocks Arrow instead of rebuilding Arrow in paimon-cpp.
# Requires Arrow built with COMPUTE/FILESYSTEM/DATASET/ACERO.
set(PAIMON_USE_EXTERNAL_ARROW ON CACHE BOOL "Use pre-built Arrow from StarRocks")

starrocks_resolve_tp_library(_STARROCKS_ARROW_LIB libarrow.a)
starrocks_resolve_tp_library(_STARROCKS_ARROW_DATASET_LIB libarrow_dataset.a)
starrocks_resolve_tp_library(_STARROCKS_ARROW_ACERO_LIB libarrow_acero.a)
starrocks_resolve_tp_library(_STARROCKS_PARQUET_LIB libparquet.a)
starrocks_resolve_tp_library(_STARROCKS_ARROW_BUNDLED_DEPS_LIB libarrow_bundled_dependencies.a)

set(PAIMON_EXTERNAL_ARROW_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "Arrow include directory")
set(PAIMON_EXTERNAL_ARROW_LIB "${_STARROCKS_ARROW_LIB}" CACHE FILEPATH "Arrow core library")
set(PAIMON_EXTERNAL_ARROW_DATASET_LIB "${_STARROCKS_ARROW_DATASET_LIB}" CACHE FILEPATH "Arrow Dataset library")
set(PAIMON_EXTERNAL_ARROW_ACERO_LIB "${_STARROCKS_ARROW_ACERO_LIB}" CACHE FILEPATH "Arrow Acero library")
set(PAIMON_EXTERNAL_PARQUET_LIB "${_STARROCKS_PARQUET_LIB}" CACHE FILEPATH "Parquet library")
set(PAIMON_EXTERNAL_ARROW_BUNDLED_DEPS_LIB "${_STARROCKS_ARROW_BUNDLED_DEPS_LIB}" CACHE FILEPATH "Arrow bundled deps library")

set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "Build with -fPIC")
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "Build type")

foreach(_starrocks_required_lib
        ZLIB_LIBRARY
        ZSTD_LIBRARY
        LZ4_LIBRARY
        SNAPPY_LIBRARY
        PAIMON_EXTERNAL_ARROW_LIB
        PAIMON_EXTERNAL_ARROW_DATASET_LIB
        PAIMON_EXTERNAL_ARROW_ACERO_LIB
        PAIMON_EXTERNAL_PARQUET_LIB
        PAIMON_EXTERNAL_ARROW_BUNDLED_DEPS_LIB)
    if(NOT EXISTS "${${_starrocks_required_lib}}")
        message(FATAL_ERROR
                "${_starrocks_required_lib} not found: ${${_starrocks_required_lib}}. "
                "Rebuild StarRocks Arrow with COMPUTE/FILESYSTEM/DATASET/ACERO enabled "
                "(./build-thirdparty.sh arrow) before building paimon_cpp.")
    endif()
endforeach()

if(NOT EXISTS "${PAIMON_EXTERNAL_ARROW_INCLUDE_DIR}/arrow/api.h")
    message(FATAL_ERROR
            "Arrow headers not found under ${PAIMON_EXTERNAL_ARROW_INCLUDE_DIR}. "
            "Rebuild StarRocks Arrow before building paimon_cpp.")
endif()

message(STATUS "========================================")
message(STATUS "Paimon-cpp Library Reuse Configuration")
message(STATUS "========================================")
message(STATUS "Reusing from StarRocks thirdparty:")
message(STATUS "  ZLIB:   ${ZLIB_LIBRARY}")
message(STATUS "  ZSTD:   ${ZSTD_LIBRARY}")
message(STATUS "  LZ4:    ${LZ4_LIBRARY}")
message(STATUS "  Snappy: ${SNAPPY_LIBRARY}")
message(STATUS "  Arrow:  ${PAIMON_EXTERNAL_ARROW_LIB}")
message(STATUS "  Dataset:${PAIMON_EXTERNAL_ARROW_DATASET_LIB}")
message(STATUS "  Acero:  ${PAIMON_EXTERNAL_ARROW_ACERO_LIB}")
message(STATUS "  Parquet:${PAIMON_EXTERNAL_PARQUET_LIB}")
message(STATUS "========================================")
