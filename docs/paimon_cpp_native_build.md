# Building the Paimon C++ reader

This is a manual Linux build sequence for the opt-in Paimon C++ reader. It builds Paimon against the same static Arrow 19 archives used by the BE and enables the BE compile-time capability. It does not run tests.

Run all commands from the StarRocks repository root. Use a supported Linux toolchain; the normal third-party build script intentionally does not build third-party dependencies on macOS.

## 1. Build third-party dependencies

For a clean third-party prefix, build the complete ordered dependency set:

```bash
./thirdparty/build-thirdparty.sh
```

For an existing prefix in which Paimon's prerequisites have already been built, rebuild Arrow before Paimon C++:

```bash
./thirdparty/build-thirdparty.sh arrow paimon_cpp
```

`paimon_cpp` uses `thirdparty/paimon-cpp-cache.cmake`. It is built statically, reuses StarRocks's Arrow, Parquet, compression, and RE2 archives, and installs its private archives under distinct names in `thirdparty/installed/lib64`.

## 2. Build FE and C++-capable BE

Build the FE normally:

```bash
./build.sh --fe
```

Build the BE with the compile-time capability enabled:

```bash
ENABLE_PAIMON_CPP=ON ./build.sh --be
```

`ENABLE_PAIMON_CPP` defaults to `OFF`. Keep it `OFF` for a standard BE build. The BE configure step fails if the required `paimon_cpp` static archives are absent, rather than producing a binary that advertises an unavailable reader.

## 3. Build a local all-in-one Docker image

After the FE and C++-capable BE artifacts are in `output/`, package them into the all-in-one image:

```bash
DOCKER_BUILDKIT=1 docker build \
  --build-arg ARTIFACT_SOURCE=local \
  --build-arg LOCAL_REPO_PATH=. \
  -f docker/dockerfiles/allin1/allin1-ubuntu.Dockerfile \
  -t starrocks/allin1-paimon-cpp:local \
  .
```

The image contains the locally built BE. A regular all-in-one image or any BE built without `ENABLE_PAIMON_CPP=ON` cannot execute ranges planned for the C++ reader. Enable `enable_paimon_cpp_reader` only after deploying the capable binary to every BE/CN that can receive the workload; use `paimon_force_jni_reader = true` as the session rollback switch.
