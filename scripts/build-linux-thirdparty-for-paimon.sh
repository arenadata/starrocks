#!/usr/bin/env bash
# Build a full Linux thirdparty tree (including Arrow Dataset/Acero + paimon-cpp)
# suitable for ./build-in-docker.sh --fe --be and the allin1 image.
#
# Why this exists:
#   Sharing starrocks/thirdparty/src between Darwin host builds and Linux
#   containers leaves Mach-O objects (jemalloc/leveldb/arrow CMake caches) that
#   break Linux linking. This script uses an isolated Linux tree under
#   STARROCKS_LINUX_THIRDPARTY so the full package list is repeatable.
#
# Usage (from repo root or anywhere):
#   ./scripts/build-linux-thirdparty-for-paimon.sh
#
# Env overrides:
#   STARROCKS_LINUX_THIRDPARTY  Install + isolated src root
#   STARROCKS_DEV_ENV_IMAGE     Docker image (default: starrocks/dev-env-ubuntu:latest)
#   PARALLEL                    Build parallelism (default: nproc inside container)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
LINUX_TP="${STARROCKS_LINUX_THIRDPARTY:-${REPO_ROOT}/../starrocks-linux-thirdparty}"
DOCKER_IMAGE="${STARROCKS_DEV_ENV_IMAGE:-starrocks/dev-env-ubuntu:latest}"

mkdir -p "${LINUX_TP}/installed" "${LINUX_TP}/src"

echo "[INFO] Repo:            ${REPO_ROOT}"
echo "[INFO] Linux thirdparty:${LINUX_TP}"
echo "[INFO] Docker image:    ${DOCKER_IMAGE}"

## 1) Seed installed/ from the official image if LLVM is missing.
#if [[ ! -f "${LINUX_TP}/installed/lib64/libLLVMInstCombine.a" && \
#      ! -f "${LINUX_TP}/installed/lib/libLLVMInstCombine.a" ]]; then
#    echo "[INFO] Seeding Linux thirdparty installed/ from ${DOCKER_IMAGE} ..."
#    docker run --rm --user root \
#        -v "${LINUX_TP}:/out" \
#        "${DOCKER_IMAGE}" \
#        bash -lc 'cp -R /var/local/thirdparty/. /out/ && chmod -R a+rwX /out || true'
#    if command -v sudo >/dev/null 2>&1; then
#        sudo chown -R "$(id -u):$(id -g)" "${LINUX_TP}" || true
#    else
#        chown -R "$(id -u):$(id -g)" "${LINUX_TP}" || true
#    fi
#fi

# 2) Full thirdparty rebuild into the isolated Linux tree.
#    - Mount LINUX_TP/src over workspace thirdparty/src so Darwin objs are never used.
#    - Mount LINUX_TP/installed over workspace thirdparty/installed as the install prefix.
#    - --clean removes extracted package dirs before building (archives stay cached).
echo "[INFO] Building full Linux thirdparty package list (isolated src + --clean) ..."
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "${REPO_ROOT}:/workspace" \
    -v "${LINUX_TP}:/var/local/thirdparty" \
    -v "${LINUX_TP}/installed:/workspace/thirdparty/installed" \
    -v "${LINUX_TP}/src:/workspace/thirdparty/src" \
    -e HOME=/tmp \
    -e STARROCKS_HOME=/workspace \
    -e STARROCKS_THIRDPARTY=/var/local/thirdparty \
    -e TAR_OPTIONS=--no-same-owner \
    -e "PARALLEL=${PARALLEL:-}" \
    --workdir /workspace \
    "${DOCKER_IMAGE}" \
    bash -lc '
      set -euo pipefail
      cd /workspace/thirdparty
      ./download-thirdparty.sh
      JOBS="${PARALLEL:-$(nproc)}"
      ./build-thirdparty.sh -j"${JOBS}" \
        libevent zlib lz4 lzo2 bzip openssl boost protobuf \
        gflags gtest glog rapidjson simdjson snappy gperftools curl \
        re2 thrift leveldb brpc rocksdb kerberos sasl \
        absl grpc flatbuffers jemalloc brotli arrow paimon_cpp

      test -f installed/lib64/libarrow_dataset.a
      test -f installed/lib64/libarrow_acero.a
      test -f installed/lib64/libpaimon.a
      ls -l installed/lib64/libarrow_dataset.a \
            installed/lib64/libarrow_acero.a \
            installed/lib64/libpaimon*.a
    '

echo "[SUCCESS] Linux thirdparty ready at ${LINUX_TP}"
echo "[NEXT] Build FE/BE with:"
echo "  DOCKER_BUILD_OPTS=\"-v ${LINUX_TP}:/var/local/thirdparty\" \\"
echo "    ${REPO_ROOT}/build-in-docker.sh --fe --be"
echo "[NEXT] Package allin1 image with:"
echo "  cd ${REPO_ROOT} && DOCKER_BUILDKIT=1 docker build \\"
echo "    --build-arg ARTIFACT_SOURCE=local --build-arg LOCAL_REPO_PATH=. \\"
echo "    -f docker/dockerfiles/allin1/allin1-ubuntu.Dockerfile \\"
echo "    -t starrocks/allin1-ubuntu-paimon:local ."
