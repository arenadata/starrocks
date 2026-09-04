#!/bin/bash
# Tests for docker/dockerfiles/be/cn_entrypoint.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test_helpers.sh"

CN_CONF='be_port = 9060
heartbeat_service_port = 9057'

# a `show compute nodes` result, columns: ComputeNodeId IP HeartbeatPort BePort HttpPort BrpcPort ...
write_compute_nodes_containing()
{
    export STUB_SHOW_COMPUTE_NODES_FILE=$FIXTURE_ROOT/compute_nodes.txt
    printf '10002\t%s\t9057\t9060\t8040\t8060\t2026-08-31 09:00:00\ttrue\n' "$1" > "$STUB_SHOW_COMPUTE_NODES_FILE"
}

test_registers_itself_and_starts_cn()
{
    setup_fixture cn
    echo "$CN_CONF" | write_conf
    export STUB_POD_IP=10.0.2.5
    write_compute_nodes_containing 10.0.2.5

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "exit status is the one of start_cn.sh"
    assert_equals "1" "$(start_calls)" "start_cn.sh is started once"
    assert_file_contains "$MYSQL_CALL_LOG" 'ALTER SYSTEM ADD COMPUTE NODE "10.0.2.5:9057";' \
        "registers itself with the heartbeat port from cn.conf"
    assert_file_not_contains "$MYSQL_CALL_LOG" "CREATE WAREHOUSE" "no warehouse is created by default"
    assert_file_contains "$START_RECORD" "PID_DIR=/tmp" "the pid file is written outside of the image"
}

test_exit_status_of_the_start_script_is_propagated()
{
    setup_fixture cn
    echo "$CN_CONF" | write_conf
    export STUB_POD_IP=10.0.2.5
    write_compute_nodes_containing 10.0.2.5
    export START_STUB_RC=5

    run_entrypoint fe-svc

    assert_equals "5" "$ENTRYPOINT_STATUS" "the entrypoint execs into start_cn.sh"
}

test_joins_the_configured_warehouse()
{
    setup_fixture cn
    echo "$CN_CONF" | write_conf
    export STUB_POD_IP=10.0.2.5
    write_compute_nodes_containing 10.0.2.5
    export KUBE_STARROCKS_MULTI_WAREHOUSE=wh1

    run_entrypoint fe-svc

    assert_file_contains "$MYSQL_CALL_LOG" "CREATE WAREHOUSE IF NOT EXISTS wh1;" "the warehouse is created"
    assert_file_contains "$MYSQL_CALL_LOG" \
        'ALTER SYSTEM ADD COMPUTE NODE "10.0.2.5:9057" INTO WAREHOUSE wh1;' "joins the warehouse"
}

test_gives_up_when_it_can_not_register()
{
    setup_fixture cn
    echo "$CN_CONF" | write_conf
    export STUB_POD_IP=10.0.2.5
    export STUB_SHOW_COMPUTE_NODES_FILE=$FIXTURE_ROOT/compute_nodes.txt
    : > "$STUB_SHOW_COMPUTE_NODES_FILE"
    export PROBE_TIMEOUT=0

    run_entrypoint fe-svc

    assert_equals "1" "$ENTRYPOINT_STATUS" "the entrypoint fails"
    assert_equals "0" "$(start_calls)" "start_cn.sh is not started"
}

test_a_configmap_mount_is_not_copied_into_the_conf_directory()
{
    setup_fixture cn
    echo "$CN_CONF" | write_conf
    export STUB_POD_IP=10.0.2.5
    write_compute_nodes_containing 10.0.2.5
    export CONFIGMAP_MOUNT_PATH=$FIXTURE_ROOT/configmap
    mkdir -p "$CONFIGMAP_MOUNT_PATH"
    printf 'be_port = 1234\n' > "$CONFIGMAP_MOUNT_PATH/cn.conf"

    run_entrypoint fe-svc

    assert_equals "1" "$(start_calls)" "start_cn.sh is started"
    assert_conf_dir_untouched "$CN_CONF"
}

test_a_read_only_configuration_directory_works()
{
    setup_fixture cn
    echo "$CN_CONF" | write_conf
    export STUB_POD_IP=10.0.2.5
    write_compute_nodes_containing 10.0.2.5
    chmod -R a-w "$CONF_DIR"

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "a read-only conf directory does not break the start"
    assert_equals "1" "$(start_calls)" "start_cn.sh is started"
    assert_conf_dir_untouched "$CN_CONF"
}

echo "cn_entrypoint.sh"
run_test test_registers_itself_and_starts_cn
run_test test_exit_status_of_the_start_script_is_propagated
run_test test_joins_the_configured_warehouse
run_test test_gives_up_when_it_can_not_register
run_test test_a_configmap_mount_is_not_copied_into_the_conf_directory
run_test test_a_read_only_configuration_directory_works
report_and_exit
