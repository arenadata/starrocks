#!/bin/bash
# End-to-end check of a membership provider. Three FEs start in parallel with the same fe.conf and
# form one cluster without ALTER SYSTEM ADD, a restarted FE stays a member, and an FE recreated with
# empty meta joins again. PROVIDER picks the scenario: embedded (default) or zookeeper; the zookeeper
# scenario adds that the bootstrap FE recreated with empty meta rejoins instead of forming a second
# cluster. Needs docker compose and the image from build-image.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

export STARROCKS_FE_IMAGE=${STARROCKS_FE_IMAGE:-starrocks-fe:membership-e2e}
PROVIDER=${PROVIDER:-embedded}
TIMEOUT=${TIMEOUT:-300}
KEEP=${KEEP:-0}

case "$PROVIDER" in
    embedded)   COMPOSE_FILES=(-f docker-compose.yml) ;;
    zookeeper)  COMPOSE_FILES=(-f docker-compose.zookeeper.yml) ;;
    *) echo "unknown PROVIDER '$PROVIDER', expected embedded or zookeeper" >&2 ; exit 2 ;;
esac

dc() { docker compose "${COMPOSE_FILES[@]}" "$@" ; }

cleanup() {
    dc logs --no-color > logs.txt 2>&1 || true
    if [[ $KEEP -eq 0 ]] ; then
        dc down -v --remove-orphans > /dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

show_frontends() {
    dc exec -T "$1" mysql -h "$1" -P 9030 -uroot -e 'SHOW FRONTENDS\G' 2> /dev/null
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
        if dc exec -T "$fe" mysql -h "$fe" -P 9030 -uroot -e 'SELECT 1' > /dev/null 2>&1 ; then
            return 0
        fi
        sleep 5
    done
    echo "FAILED: $fe does not answer queries" >&2
    return 1
}

echo "== start three FEs in parallel (provider: $PROVIDER)"
dc up -d
wait_for_cluster fe1 3
for fe in fe1 fe2 fe3 ; do
    wait_for_query "$fe"
done

echo "== fe2 restarts with its meta"
dc restart fe2
wait_for_query fe2
wait_for_cluster fe1 3

echo "== fe3 is recreated with empty meta"
dc rm -sf fe3 > /dev/null
dc up -d fe3
wait_for_query fe3
wait_for_cluster fe1 3

if [[ "$PROVIDER" == "zookeeper" ]] ; then
    echo "== fe1, the FE that bootstrapped, is recreated with empty meta"
    dc rm -sf fe1 > /dev/null
    dc up -d fe1
    wait_for_query fe1
    wait_for_cluster fe1 3
fi
echo "OK"
