#!/bin/bash
# Builds the FE image used by run.sh from the artifacts in output/fe (produced by ./build.sh --fe).
# The build context holds only output/fe and the entrypoint scripts, so the repository itself is
# never sent to the docker daemon.
set -euo pipefail

repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
image=${STARROCKS_FE_IMAGE:-starrocks-fe:membership-e2e}

if [[ ! -f "$repo/output/fe/lib/starrocks-fe.jar" ]] ; then
    echo "output/fe/lib/starrocks-fe.jar not found, run ./build.sh --fe first" >&2
    exit 1
fi

context=$(mktemp -d)
trap 'rm -rf "$context"' EXIT
mkdir -p "$context/output" "$context/docker/dockerfiles"
cp -r "$repo/output/fe" "$context/output/fe"
rm -rf "$context/output/fe/meta" "$context/output/fe/log"
cp -r "$repo/docker/dockerfiles/fe" "$repo/docker/dockerfiles/common" "$context/docker/dockerfiles/"

docker build \
    -f "$context/docker/dockerfiles/fe/fe-ubuntu.Dockerfile" \
    --build-arg ARTIFACT_SOURCE=local \
    --build-arg LOCAL_REPO_PATH=. \
    --build-arg MINIMAL=true \
    -t "$image" \
    "$context"
echo "built $image"
