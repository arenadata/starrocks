#!/bin/bash
# Tests for docker/dockerfiles/common/entrypoint_common.sh

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/test_helpers.sh"

# eval_in_common <shell code> : runs the code with entrypoint_common.sh sourced, echoes its stdout
eval_in_common()
{
    ( source "$FIXTURE_ROOT/entrypoint_common.sh" ; eval "$1" ) 2>/dev/null
}

test_pid_dir_defaults_to_tmp()
{
    setup_fixture fe
    assert_equals "/tmp" "$(eval_in_common 'echo $PID_DIR')" "PID_DIR defaults to /tmp"
}

test_pid_dir_from_environment_is_kept()
{
    setup_fixture fe
    export PID_DIR=/var/run/starrocks
    assert_equals "/var/run/starrocks" "$(eval_in_common 'echo $PID_DIR')" "PID_DIR from the environment wins"
}

test_mysql_history_is_disabled()
{
    setup_fixture fe
    assert_equals "/dev/null" "$(eval_in_common 'echo $MYSQL_HISTFILE')" "mysql must not write a history file"
}

test_parse_confval_reads_a_value()
{
    setup_fixture fe
    write_conf <<'CONF'
# query_port = 1111
query_port = 9999
edit_log_port=9011
  http_port = 8030
CONF
    local conf=$CONF_DIR/fe.conf
    assert_equals "9999" "$(eval_in_common "parse_confval_from_conf $conf query_port")" "value with spaces"
    assert_equals "9011" "$(eval_in_common "parse_confval_from_conf $conf edit_log_port")" "value without spaces"
    assert_equals "8030" "$(eval_in_common "parse_confval_from_conf $conf http_port")" "indented key"
    assert_equals "" "$(eval_in_common "parse_confval_from_conf $conf rpc_port")" "key that is not set"
}

test_parse_confval_survives_a_missing_file()
{
    setup_fixture fe
    assert_equals "" "$(eval_in_common "parse_confval_from_conf $CONF_DIR/nope.conf query_port")" \
        "a missing conf file yields an empty value"
}

test_collect_host_info_defaults_to_ip()
{
    setup_fixture fe
    export STUB_POD_IP=10.1.2.3 STUB_POD_FQDN=sr-fe-0.svc.local
    local out
    out=$(eval_in_common 'HOST_TYPE=IP; collect_host_info; echo "$MY_IP|$MY_HOSTNAME|$MY_SELF"')
    assert_equals "10.1.2.3|sr-fe-0.svc.local|10.1.2.3" "$out" "HOST_TYPE=IP registers the address"
}

test_collect_host_info_honours_fqdn()
{
    setup_fixture fe
    export STUB_POD_IP=10.1.2.3 STUB_POD_FQDN=sr-fe-0.svc.local
    local out
    out=$(eval_in_common 'HOST_TYPE=FQDN; collect_host_info; echo "$MY_SELF"')
    assert_equals "sr-fe-0.svc.local" "$out" "HOST_TYPE=FQDN registers the hostname"
}

test_collect_host_info_prefers_the_pod_environment()
{
    setup_fixture fe
    export STUB_POD_IP=10.1.2.3 STUB_POD_FQDN=from-hostname
    export POD_IP=192.168.0.9 POD_FQDN=from-downward-api
    local out
    out=$(eval_in_common 'collect_host_info; echo "$MY_IP|$MY_HOSTNAME"')
    assert_equals "192.168.0.9|from-downward-api" "$out" "POD_IP/POD_FQDN win over hostname"
}

test_sr_mysql_command_line()
{
    setup_fixture fe
    eval_in_common 'sr_mysql fe-svc 9030 "show frontends;"' > /dev/null
    local call
    call=$(cat "$MYSQL_CALL_LOG")
    assert_contains "$call" "-h fe-svc" "host is passed"
    assert_contains "$call" "-P 9030" "port is passed"
    assert_contains "$call" "-u root" "user is passed"
    assert_contains "$call" "show frontends;" "statement is passed"
}

echo "entrypoint_common.sh"
run_test test_pid_dir_defaults_to_tmp
run_test test_pid_dir_from_environment_is_kept
run_test test_mysql_history_is_disabled
run_test test_parse_confval_reads_a_value
run_test test_parse_confval_survives_a_missing_file
run_test test_collect_host_info_defaults_to_ip
run_test test_collect_host_info_honours_fqdn
run_test test_collect_host_info_prefers_the_pod_environment
run_test test_sr_mysql_command_line
report_and_exit
