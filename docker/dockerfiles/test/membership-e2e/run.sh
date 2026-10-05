#!/bin/bash
# End-to-end check of the embedded membership provider: three FEs start in parallel with the same
# fe.conf and form one cluster without ALTER SYSTEM ADD, a restarted FE stays a member, and an FE
# recreated with empty meta joins again. Needs docker compose and the image from build-image.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

export STARROCKS_FE_IMAGE=${STARROCKS_FE_IMAGE:-starrocks-fe:membership-e2e}
TIMEOUT=${TIMEOUT:-300}
KEEP=${KEEP:-0}

cleanup() {
    docker compose logs --no-color > logs.txt 2>&1 || true
    if [[ $KEEP -eq 0 ]] ; then
        docker compose down -v --remove-orphans > /dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

show_frontends() {
    docker compose exec -T "$1" mysql -h "$1" -P 9030 -uroot -e 'SHOW FRONTENDS\G' 2> /dev/null
}

# wait_for_cluster <fe to ask> <expected alive members>
wait_for_cluster() {
    local via=$1 expected=$2 deadline=$((SECONDS + TIMEOUT)) out alive leaders
    while (( SECONDS < deadline )) ; do
        out=$(show_frontends "$via" || true)
        alive=$(grep -c '^ *Alive: true' <<< "$out" || true)
        leaders=$(grep -c '^ *Role: LEADER' <<< "$out" || true)
        if [[ $alive -eq $expected && $leaders -eq 1 ]] ; then
            echo "$out" | grep -E '^ *(Name|Role|Alive|IsHelper):'
            return 0
        fi
        sleep 5
    done
    echo "FAILED: expected $expected alive frontends and one leader, got:" >&2
    echo "$out" >&2
    return 1
}

# wait_for_query <fe>: the FE serves queries, i.e. it replayed the journal after joining
wait_for_query() {
    local fe=$1 deadline=$((SECONDS + TIMEOUT))
    while (( SECONDS < deadline )) ; do
        if docker compose exec -T "$fe" mysql -h "$fe" -P 9030 -uroot -e 'SELECT 1' > /dev/null 2>&1 ; then
            return 0
        fi
        sleep 5
    done
    echo "FAILED: $fe does not answer queries" >&2
    return 1
}

echo "== start three FEs in parallel"
docker compose up -d
wait_for_cluster fe1 3
for fe in fe1 fe2 fe3 ; do
    wait_for_query "$fe"
done

echo "== fe2 restarts with its meta"
docker compose restart fe2
wait_for_query fe2
wait_for_cluster fe1 3

echo "== fe3 is recreated with empty meta"
docker compose rm -sf fe3 > /dev/null
docker compose up -d fe3
wait_for_query fe3
wait_for_cluster fe1 3
echo "OK"
