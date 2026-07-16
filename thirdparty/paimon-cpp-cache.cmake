# StarRocks paimon-cpp 0.2.0 cache.
#
# Keep Paimon static and reuse the Arrow/compression ABI already built by
# StarRocks. fmt, oneTBB, xxHash and roaring remain Paimon's pinned bundled
# versions and are installed under distinct names by build-thirdparty.sh.

set(_paimon_starrocks_prefix "$ENV{TP_INSTALL_DIR}")
if(NOT _paimon_starrocks_prefix)
    message(FATAL_ERROR "TP_INSTALL_DIR must be exported before configuring paimon-cpp")
endif()

function(_paimon_require_static_library output basename)
    foreach(_libdir lib64 lib)
        set(_candidate
            "${_paimon_starrocks_prefix}/${_libdir}/lib${basename}.a")
        if(EXISTS "${_candidate}")
            set(${output}
                "${_candidate}"
                CACHE FILEPATH "StarRocks static ${basename}" FORCE)
            return()
        endif()
    endforeach()
    message(FATAL_ERROR
            "Required StarRocks static library was not found: ${basename} "
            "(searched lib64 and lib under ${_paimon_starrocks_prefix})")
endfunction()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON CACHE BOOL "" FORCE)
set(CMAKE_C_VISIBILITY_PRESET hidden CACHE STRING "" FORCE)
set(CMAKE_CXX_VISIBILITY_PRESET hidden CACHE STRING "" FORCE)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON CACHE BOOL "" FORCE)
set(PAIMON_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(PAIMON_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(PAIMON_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PAIMON_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
# Required for default Paimon manifest.format=avro (and Avro data files).
# Reuse StarRocks's libavrocpp_s.a so the BE does not link two Avro C++ copies.
set(PAIMON_ENABLE_AVRO ON CACHE BOOL "" FORCE)
set(Avro_SOURCE SYSTEM CACHE STRING "" FORCE)
set(Avro_ROOT "${_paimon_starrocks_prefix}" CACHE PATH "" FORCE)
# Pre-seed FindAvroAlt: StarRocks installs headers under include/avrocpp and
# build_paimon_cpp exposes them as include/avro. Seeding also works around older
# FindAvroAlt copies that put HINTS inside NAMES.
set(AVRO_INCLUDE_DIR "${_paimon_starrocks_prefix}/include" CACHE PATH "" FORCE)
_paimon_require_static_library(AVRO_LIBRARY avrocpp_s)
set(PAIMON_ENABLE_ORC OFF CACHE BOOL "" FORCE)
set(PAIMON_ENABLE_LANCE OFF CACHE BOOL "" FORCE)
set(PAIMON_ENABLE_JINDO OFF CACHE BOOL "" FORCE)
set(PAIMON_ENABLE_LUMINA OFF CACHE BOOL "" FORCE)
set(PAIMON_ENABLE_LUCENE OFF CACHE BOOL "" FORCE)
set(PAIMON_ENABLE_TANTIVY OFF CACHE BOOL "" FORCE)
set(PAIMON_DEPENDENCY_USE_SHARED OFF CACHE BOOL "" FORCE)
set(PAIMON_USE_EXTERNAL_ARROW ON CACHE BOOL "" FORCE)

# Arrow is marked BUNDLED so paimon-cpp follows its supported resolver path;
# the StarRocks patch replaces that build rule with imported Arrow 19 archives.
set(Arrow_SOURCE BUNDLED CACHE STRING "" FORCE)
set(zstd_SOURCE SYSTEM CACHE STRING "" FORCE)
set(Snappy_SOURCE SYSTEM CACHE STRING "" FORCE)
set(LZ4_SOURCE SYSTEM CACHE STRING "" FORCE)
set(ZLIB_SOURCE SYSTEM CACHE STRING "" FORCE)
set(RE2_SOURCE SYSTEM CACHE STRING "" FORCE)
set(RapidJSON_SOURCE SYSTEM CACHE STRING "" FORCE)
set(glog_SOURCE SYSTEM CACHE STRING "" FORCE)
set(fmt_SOURCE BUNDLED CACHE STRING "" FORCE)
set(TBB_SOURCE BUNDLED CACHE STRING "" FORCE)

set(PAIMON_PACKAGE_PREFIX
    "${_paimon_starrocks_prefix}"
    CACHE PATH "StarRocks third-party prefix" FORCE)
set(CMAKE_PREFIX_PATH
    "${_paimon_starrocks_prefix};${_paimon_starrocks_prefix}/lib/cmake;${_paimon_starrocks_prefix}/lib64/cmake"
    CACHE STRING "" FORCE)
set(RapidJSON_ROOT "${_paimon_starrocks_prefix}" CACHE PATH "" FORCE)
set(glog_ROOT "${_paimon_starrocks_prefix}" CACHE PATH "" FORCE)
set(RE2_ROOT "${_paimon_starrocks_prefix}" CACHE PATH "" FORCE)

set(SNAPPY_INCLUDE_DIR "${_paimon_starrocks_prefix}/include" CACHE PATH "" FORCE)
set(LZ4_INCLUDE_DIR "${_paimon_starrocks_prefix}/include/lz4" CACHE PATH "" FORCE)
set(ZSTD_INCLUDE_DIR "${_paimon_starrocks_prefix}/include/zstd" CACHE PATH "" FORCE)
set(ZLIB_INCLUDE_DIR "${_paimon_starrocks_prefix}/include" CACHE PATH "" FORCE)
set(RE2_INCLUDE_DIR "${_paimon_starrocks_prefix}/include" CACHE PATH "" FORCE)
_paimon_require_static_library(SNAPPY_LIBRARY snappy)
_paimon_require_static_library(LZ4_LIBRARY lz4)
_paimon_require_static_library(ZSTD_LIBRARY zstd)
_paimon_require_static_library(ZLIB_LIBRARY z)
_paimon_require_static_library(RE2_LIBRARY re2)

unset(_paimon_starrocks_prefix)
