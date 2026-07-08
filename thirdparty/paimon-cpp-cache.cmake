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

set(CMAKE_PREFIX_PATH "${STARROCKS_THIRDPARTY_DIR};${CMAKE_PREFIX_PATH}" CACHE STRING "Search path for find_package")

set(ZLIB_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "ZLIB root directory")
set(ZLIB_LIBRARY "${STARROCKS_LIB_DIR}/libz.a" CACHE FILEPATH "ZLIB library")
set(ZLIB_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "ZLIB include directory")

set(ZSTD_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "ZSTD root directory")
set(ZSTD_LIBRARY "${STARROCKS_LIB_DIR}/libzstd.a" CACHE FILEPATH "ZSTD library")
set(ZSTD_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "ZSTD include directory")

set(LZ4_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "LZ4 root directory")
set(LZ4_LIBRARY "${STARROCKS_LIB_DIR}/liblz4.a" CACHE FILEPATH "LZ4 library")
set(LZ4_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "LZ4 include directory")

set(SNAPPY_ROOT "${STARROCKS_THIRDPARTY_DIR}" CACHE PATH "Snappy root directory")
set(SNAPPY_LIBRARY "${STARROCKS_LIB_DIR}/libsnappy.a" CACHE FILEPATH "Snappy library")
set(SNAPPY_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "Snappy include directory")

# Reuse StarRocks Arrow instead of rebuilding Arrow in paimon-cpp.
set(PAIMON_USE_EXTERNAL_ARROW ON CACHE BOOL "Use pre-built Arrow from StarRocks")
set(PAIMON_EXTERNAL_ARROW_INCLUDE_DIR "${STARROCKS_INCLUDE_DIR}" CACHE PATH "Arrow include directory")
set(PAIMON_EXTERNAL_ARROW_LIB "${STARROCKS_LIB64_DIR}/libarrow.a" CACHE FILEPATH "Arrow core library")
set(PAIMON_EXTERNAL_ARROW_DATASET_LIB "${STARROCKS_LIB64_DIR}/libarrow_dataset.a" CACHE FILEPATH "Arrow Dataset library")
set(PAIMON_EXTERNAL_ARROW_ACERO_LIB "${STARROCKS_LIB64_DIR}/libarrow_acero.a" CACHE FILEPATH "Arrow Acero library")
set(PAIMON_EXTERNAL_PARQUET_LIB "${STARROCKS_LIB64_DIR}/libparquet.a" CACHE FILEPATH "Parquet library")
set(PAIMON_EXTERNAL_ARROW_BUNDLED_DEPS_LIB "${STARROCKS_LIB64_DIR}/libarrow_bundled_dependencies.a" CACHE FILEPATH "Arrow bundled deps library")

set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "Build with -fPIC")
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "Build type")
