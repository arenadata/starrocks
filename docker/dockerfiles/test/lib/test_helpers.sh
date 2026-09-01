#!/bin/bash
# Minimal assertion and fixture helpers for the entrypoint script tests.
# No external dependencies, works with the bash 3.2 shipped by macOS.

TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCKERFILES_DIR="$(cd "$TEST_DIR/.." && pwd)"
STUBS_DIR="$TEST_DIR/stubs"

TESTS_RUN=0
TESTS_FAILED=0
CURRENT_TEST=
CURRENT_TEST_FAILED=0

# ---------------------------------------------------------------------------- assertions

fail()
{
    CURRENT_TEST_FAILED=1
    echo "    FAIL: $*" >&2
}

assert_equals()
{
    local expected=$1 actual=$2 msg=$3
    if [[ "$expected" != "$actual" ]] ; then
        fail "$msg: expected [$expected], got [$actual]"
    fi
}

assert_contains()
{
    local haystack=$1 needle=$2 msg=$3
    case "$haystack" in
        *"$needle"*) ;;
        *) fail "$msg: [$needle] not found in [$haystack]" ;;
    esac
}

assert_not_contains()
{
    local haystack=$1 needle=$2 msg=$3
    case "$haystack" in
        *"$needle"*) fail "$msg: [$needle] unexpectedly found in [$haystack]" ;;
    esac
}

assert_file_contains()
{
    local file=$1 needle=$2 msg=$3
    if [[ ! -f "$file" ]] ; then
        fail "$msg: file $file does not exist"
        return
    fi
    assert_contains "$(cat "$file")" "$needle" "$msg"
}

assert_file_not_contains()
{
    local file=$1 needle=$2 msg=$3
    if [[ ! -f "$file" ]] ; then
        return
    fi
    assert_not_contains "$(cat "$file")" "$needle" "$msg"
}

# ---------------------------------------------------------------------------- runner

run_test()
{
    local name=$1
    CURRENT_TEST=$name
    CURRENT_TEST_FAILED=0
    TESTS_RUN=$((TESTS_RUN + 1))
    echo "  - $name"
    "$name"
    if [[ $CURRENT_TEST_FAILED -ne 0 ]] ; then
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
    teardown_fixture
}

report_and_exit()
{
    echo
    if [[ $TESTS_FAILED -ne 0 ]] ; then
        echo "$TESTS_FAILED of $TESTS_RUN test(s) FAILED"
        exit 1
    fi
    echo "all $TESTS_RUN test(s) passed"
    exit 0
}

# ---------------------------------------------------------------------------- fixture

# setup_fixture <fe|be|cn>
# Builds a throw-away installation that mirrors the layout of the container images:
#
#   $FIXTURE_ROOT/<component>_entrypoint.sh   (the script under test)
#   $FIXTURE_ROOT/entrypoint_common.sh
#   $FIXTURE_ROOT/<component>/conf/<component>.conf
#   $FIXTURE_ROOT/<component>/bin/start_<component>.sh   (recording stub)
#
# and points PATH at the stubs of mysql/nc/hostname/timeout.
setup_fixture()
{
    local component=$1

    FIXTURE_ROOT=`mktemp -d "${TMPDIR:-/tmp}/sr-entrypoint-test.XXXXXX"`
    FIXTURE_COMPONENT=$component
    CONF_DIR=$FIXTURE_ROOT/$component/conf
    START_RECORD=$FIXTURE_ROOT/start_record.txt
    START_STUB_COUNT=$FIXTURE_ROOT/start_count.txt
    MYSQL_CALL_LOG=$FIXTURE_ROOT/mysql_calls.txt
    NC_CALL_LOG=$FIXTURE_ROOT/nc_calls.txt
    ENTRYPOINT_LOG=$FIXTURE_ROOT/entrypoint.log

    mkdir -p "$CONF_DIR" "$FIXTURE_ROOT/$component/bin" "$FIXTURE_ROOT/$component/log"

    local src_dir=$DOCKERFILES_DIR/fe
    if [[ "$component" != "fe" ]] ; then
        src_dir=$DOCKERFILES_DIR/be
    fi
    cp "$src_dir/${component}_entrypoint.sh" "$FIXTURE_ROOT/"
    cp "$DOCKERFILES_DIR/common/entrypoint_common.sh" "$FIXTURE_ROOT/"
    cp "$STUBS_DIR/start_script_stub.sh" "$FIXTURE_ROOT/$component/bin/start_${component}.sh"
    cp "$STUBS_DIR/noop.sh" "$FIXTURE_ROOT/upload_coredump.sh"
    chmod +x "$FIXTURE_ROOT/$component/bin/start_${component}.sh" "$FIXTURE_ROOT/upload_coredump.sh" \
        "$FIXTURE_ROOT/${component}_entrypoint.sh"

    : > "$START_RECORD"
    : > "$MYSQL_CALL_LOG"
    : > "$NC_CALL_LOG"

    export STARROCKS_ROOT=$FIXTURE_ROOT
    export START_RECORD START_STUB_COUNT MYSQL_CALL_LOG NC_CALL_LOG
    export PATH="$STUBS_DIR:$PATH"

    # keep the tests fast: no sleeping between retries
    export PROBE_INTERVAL=0

    unset START_STUB_RC START_STUB_RC_1 START_STUB_RC_2 START_STUB_RC_3
    unset MYSQL_STUB_RC NC_STUB_RC HOST_TYPE LOG_CONSOLE DEBUG_MODE COREDUMP_ENABLED
    unset POD_IP POD_FQDN PID_DIR CONFIGMAP_MOUNT_PATH KUBE_STARROCKS_MULTI_WAREHOUSE
    unset STUB_SHOW_FRONTENDS_FILE STUB_SHOW_BACKENDS_FILE STUB_SHOW_COMPUTE_NODES_FILE
    unset PROBE_LEADER_POD0_TIMEOUT PROBE_LEADER_PODX_TIMEOUT PROBE_TIMEOUT ADD_SELF_TIMEOUT
    unset STUB_POD_IP STUB_POD_FQDN BE_RESTART_WAIT_SECONDS
    unset SR_MYSQL_SSL_MODE SR_MYSQL_SSL_CA
}

teardown_fixture()
{
    if [[ -n "$FIXTURE_ROOT" && -d "$FIXTURE_ROOT" ]] ; then
        chmod -R u+w "$FIXTURE_ROOT" 2>/dev/null
        rm -rf "$FIXTURE_ROOT"
    fi
    FIXTURE_ROOT=
}

write_conf()
{
    cat > "$CONF_DIR/${FIXTURE_COMPONENT}.conf"
}

# run_entrypoint [args...] -> exit status in $ENTRYPOINT_STATUS, output in $ENTRYPOINT_LOG
run_entrypoint()
{
    "$FIXTURE_ROOT/${FIXTURE_COMPONENT}_entrypoint.sh" "$@" > "$ENTRYPOINT_LOG" 2>&1
    ENTRYPOINT_STATUS=$?
}

start_args()
{
    grep '^args:' "$START_RECORD" | tail -1 | sed 's|^args: *||'
}

start_calls()
{
    local n=0
    if [[ -f "$START_RECORD" ]] ; then
        # grep -c prints 0 and exits non-zero when there is no match
        n=`grep -c '^call:' "$START_RECORD"`
    fi
    echo "$n"
}

# The entrypoint scripts must never modify the configuration directory: it is mounted read-only from a
# ConfigMap/Secret volume and the root filesystem of the container may be read-only as well.
assert_conf_dir_untouched()
{
    local expected=$1
    local listing=`ls -a "$CONF_DIR" | tr '\n' ' '`
    assert_equals ". .. ${FIXTURE_COMPONENT}.conf " "$listing" "no file added to or removed from conf/"
    if [[ -L "$CONF_DIR/${FIXTURE_COMPONENT}.conf" ]] ; then
        fail "conf/${FIXTURE_COMPONENT}.conf was replaced by a symlink"
    fi
    assert_equals "$expected" "$(cat "$CONF_DIR/${FIXTURE_COMPONENT}.conf")" "conf file content unchanged"
}
