#!/bin/bash
# Tests for docker/dockerfiles/fe/fe_entrypoint.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test_helpers.sh"

FE_CONF='query_port = 9030
edit_log_port = 9010'

# a `show frontends` result with a leader, columns: Id Name IP EditLogPort HttpPort QueryPort RpcPort Role ...
write_frontends_with_leader()
{
    export STUB_SHOW_FRONTENDS_FILE=$FIXTURE_ROOT/frontends.txt
    cat > "$STUB_SHOW_FRONTENDS_FILE" <<'LIST'
1	sr-fe-0_10.0.0.5_9010	10.0.0.5	9010	8030	9030	9020	LEADER	1868191	true	true	42	2026-08-31 10:00:00	true		2026-08-31 09:00:00	3.5.0
LIST
}

with_existing_meta()
{
    mkdir -p "$FIXTURE_ROOT/fe/meta/image"
    touch "$FIXTURE_ROOT/fe/meta/image/ROLE"
}

test_start_with_existing_meta()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf
    with_existing_meta

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "exit status is the one of start_fe.sh"
    assert_equals "1" "$(start_calls)" "start_fe.sh is started once"
    assert_equals "--host_type IP" "$(start_args)" "the host type is passed on"
    assert_equals "" "$(cat "$MYSQL_CALL_LOG")" "an existing meta needs no FE round trip"
    assert_file_contains "$START_RECORD" "PID_DIR=/tmp" "the pid file is written outside of the image"
    assert_file_contains "$START_RECORD" "MYSQL_HISTFILE=/dev/null" "no mysql history file"
}

test_exit_status_of_the_start_script_is_propagated()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf
    with_existing_meta
    export START_STUB_RC=7

    run_entrypoint fe-svc

    assert_equals "7" "$ENTRYPOINT_STATUS" "the entrypoint execs into start_fe.sh"
}

test_log_console_is_forwarded()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf
    with_existing_meta
    export LOG_CONSOLE=1

    run_entrypoint fe-svc

    assert_contains "$(start_args)" "--logconsole" "LOG_CONSOLE=1 enables console logging"
}

test_first_start_joins_the_existing_leader()
{
    setup_fixture fe
    printf 'query_port = 9999\nedit_log_port = 9011\n' | write_conf
    export STUB_POD_IP=10.0.0.9 STUB_POD_FQDN=sr-fe-1.sr-fe-svc.sr.svc.cluster.local
    write_frontends_with_leader
    # the leader reports the new follower as soon as it has been added
    printf '2\tsr-fe-1_10.0.0.9_9011\t10.0.0.9\t9011\t8030\t9999\t9020\tFOLLOWER\t1868191\ttrue\ttrue\n' \
        >> "$STUB_SHOW_FRONTENDS_FILE"

    run_entrypoint fe-svc

    assert_equals "1" "$(start_calls)" "start_fe.sh is started once"
    assert_contains "$(start_args)" "--helper 10.0.0.5:9011" "the leader is passed as the helper"
    assert_file_contains "$MYSQL_CALL_LOG" 'ALTER SYSTEM ADD FOLLOWER "10.0.0.9:9011";' "registers itself"
    assert_file_contains "$MYSQL_CALL_LOG" "-P 9999" "the query port comes from fe.conf"
}

test_first_start_of_the_first_pod_without_members()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf
    export STUB_POD_FQDN=sr-fe-0.sr-fe-svc.sr.svc.cluster.local
    # nothing is listening yet and the probe gives up immediately
    export NC_STUB_RC=1 PROBE_LEADER_POD0_TIMEOUT=0

    run_entrypoint fe-svc

    assert_equals "1" "$(start_calls)" "the first FE starts on its own"
    assert_not_contains "$(start_args)" "--helper" "there is no leader to point at"
    assert_equals "" "$(cat "$MYSQL_CALL_LOG")" "no statement is sent while the service is down"
}

test_first_start_of_a_later_pod_gives_up_without_a_leader()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf
    export STUB_POD_FQDN=sr-fe-2.sr-fe-svc.sr.svc.cluster.local
    export NC_STUB_RC=1 PROBE_LEADER_PODX_TIMEOUT=0

    run_entrypoint fe-svc

    assert_equals "1" "$ENTRYPOINT_STATUS" "the entrypoint fails instead of starting a split brain"
    assert_equals "0" "$(start_calls)" "start_fe.sh is not started"
}

test_missing_service_name_is_rejected()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf

    run_entrypoint

    assert_equals "1" "$ENTRYPOINT_STATUS" "the FE service name is required"
    assert_equals "0" "$(start_calls)" "start_fe.sh is not started"
}

# The configuration is mounted over conf/ nowadays, the entrypoint must not copy anything into it.
test_a_configmap_mount_is_not_copied_into_the_conf_directory()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf
    with_existing_meta
    # a configmap mounted the way the operator used to mount it, conf/ itself stays writable here so that
    # the test fails if the entrypoint starts backing up and symlinking files again
    export CONFIGMAP_MOUNT_PATH=$FIXTURE_ROOT/configmap
    mkdir -p "$CONFIGMAP_MOUNT_PATH"
    printf 'query_port = 1234\n' > "$CONFIGMAP_MOUNT_PATH/fe.conf"

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "the start is not affected"
    assert_equals "1" "$(start_calls)" "start_fe.sh is started"
    assert_conf_dir_untouched "$FE_CONF"
}

test_a_read_only_configuration_directory_works()
{
    setup_fixture fe
    echo "$FE_CONF" | write_conf
    with_existing_meta
    chmod -R a-w "$CONF_DIR"

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "a read-only conf directory does not break the start"
    assert_equals "1" "$(start_calls)" "start_fe.sh is started"
    assert_conf_dir_untouched "$FE_CONF"
}

echo "fe_entrypoint.sh"
run_test test_start_with_existing_meta
run_test test_exit_status_of_the_start_script_is_propagated
run_test test_log_console_is_forwarded
run_test test_first_start_joins_the_existing_leader
run_test test_first_start_of_the_first_pod_without_members
run_test test_first_start_of_a_later_pod_gives_up_without_a_leader
run_test test_missing_service_name_is_rejected
run_test test_a_configmap_mount_is_not_copied_into_the_conf_directory
run_test test_a_read_only_configuration_directory_works
report_and_exit
