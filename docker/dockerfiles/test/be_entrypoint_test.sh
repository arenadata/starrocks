#!/bin/bash
# Tests for docker/dockerfiles/be/be_entrypoint.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test_helpers.sh"

BE_CONF='be_port = 9060
heartbeat_service_port = 9055'

# a `show backends` result, columns: BackendId IP HeartbeatPort BePort HttpPort BrpcPort ...
write_backends_containing()
{
    export STUB_SHOW_BACKENDS_FILE=$FIXTURE_ROOT/backends.txt
    printf '10001\t%s\t9055\t9060\t8040\t8060\t2026-08-31 09:00:00\ttrue\n' "$1" > "$STUB_SHOW_BACKENDS_FILE"
}

test_registers_itself_and_starts_be()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export STUB_POD_IP=10.0.1.5
    write_backends_containing 10.0.1.5

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "exit status is the one of start_be.sh"
    assert_equals "1" "$(start_calls)" "start_be.sh is started once"
    assert_file_contains "$MYSQL_CALL_LOG" 'ALTER SYSTEM ADD BACKEND "10.0.1.5:9055";' \
        "registers itself with the heartbeat port from be.conf"
    assert_file_contains "$START_RECORD" "PID_DIR=/tmp" "the pid file is written outside of the image"
    assert_file_contains "$START_RECORD" "MYSQL_HISTFILE=/dev/null" "no mysql history file"
}

test_registers_with_the_fqdn()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export HOST_TYPE=FQDN STUB_POD_FQDN=sr-be-0.sr-be-svc.sr.svc.cluster.local
    write_backends_containing sr-be-0.sr-be-svc.sr.svc.cluster.local

    run_entrypoint fe-svc

    assert_file_contains "$MYSQL_CALL_LOG" \
        'ALTER SYSTEM ADD BACKEND "sr-be-0.sr-be-svc.sr.svc.cluster.local:9055";' "registers its FQDN"
    assert_equals "1" "$(start_calls)" "start_be.sh is started once"
}

# In production the entrypoint execs into start_be.sh, so its status is the status of the container and a
# crashed BE is restarted by the orchestrator, not by a shell inside the container.
test_production_mode_execs_into_start_be()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export STUB_POD_IP=10.0.1.5
    write_backends_containing 10.0.1.5
    export START_STUB_RC=3

    run_entrypoint fe-svc

    assert_equals "3" "$ENTRYPOINT_STATUS" "the exit status of BE is the exit status of the entrypoint"
    assert_equals "1" "$(start_calls)" "a crashed BE is not restarted inside the container"
}

test_coredump_mode_restarts_be_after_a_crash()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export STUB_POD_IP=10.0.1.5
    write_backends_containing 10.0.1.5
    export COREDUMP_ENABLED=true BE_RESTART_WAIT_SECONDS=0 LOG_CONSOLE=1
    # SIGSEGV first, a clean exit on the second run
    export START_STUB_RC_1=139 START_STUB_RC_2=0

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "the loop ends with the status of the last run"
    assert_equals "2" "$(start_calls)" "BE is restarted once so that the core file can be collected"
}

test_gives_up_when_it_can_not_register()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export STUB_POD_IP=10.0.1.5
    # the FE never reports this backend
    export STUB_SHOW_BACKENDS_FILE=$FIXTURE_ROOT/backends.txt
    : > "$STUB_SHOW_BACKENDS_FILE"
    export PROBE_TIMEOUT=0

    run_entrypoint fe-svc

    assert_equals "1" "$ENTRYPOINT_STATUS" "the entrypoint fails"
    assert_equals "0" "$(start_calls)" "start_be.sh is not started"
}

test_log_console_is_forwarded()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export STUB_POD_IP=10.0.1.5
    write_backends_containing 10.0.1.5
    export LOG_CONSOLE=1

    run_entrypoint fe-svc

    assert_contains "$(start_args)" "--logconsole" "LOG_CONSOLE=1 enables console logging"
}

test_a_configmap_mount_is_not_copied_into_the_conf_directory()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export STUB_POD_IP=10.0.1.5
    write_backends_containing 10.0.1.5
    export CONFIGMAP_MOUNT_PATH=$FIXTURE_ROOT/configmap
    mkdir -p "$CONFIGMAP_MOUNT_PATH"
    printf 'be_port = 1234\n' > "$CONFIGMAP_MOUNT_PATH/be.conf"

    run_entrypoint fe-svc

    assert_equals "1" "$(start_calls)" "start_be.sh is started"
    assert_conf_dir_untouched "$BE_CONF"
}

test_a_read_only_configuration_directory_works()
{
    setup_fixture be
    echo "$BE_CONF" | write_conf
    export STUB_POD_IP=10.0.1.5
    write_backends_containing 10.0.1.5
    chmod -R a-w "$CONF_DIR"

    run_entrypoint fe-svc

    assert_equals "0" "$ENTRYPOINT_STATUS" "a read-only conf directory does not break the start"
    assert_equals "1" "$(start_calls)" "start_be.sh is started"
    assert_conf_dir_untouched "$BE_CONF"
}

echo "be_entrypoint.sh"
run_test test_registers_itself_and_starts_be
run_test test_registers_with_the_fqdn
run_test test_production_mode_execs_into_start_be
run_test test_coredump_mode_restarts_be_after_a_crash
run_test test_gives_up_when_it_can_not_register
run_test test_log_console_is_forwarded
run_test test_a_configmap_mount_is_not_copied_into_the_conf_directory
run_test test_a_read_only_configuration_directory_works
report_and_exit
