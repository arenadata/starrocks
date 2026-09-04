#!/bin/bash

ENTRYPOINT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$ENTRYPOINT_DIR/entrypoint_common.sh"

HOST_TYPE=${HOST_TYPE:-"IP"}
FE_QUERY_PORT=${FE_QUERY_PORT:-9030}
PROBE_TIMEOUT=${PROBE_TIMEOUT:-60}
PROBE_INTERVAL=${PROBE_INTERVAL:-2}
HEARTBEAT_PORT=9050
MY_SELF=
STARROCKS_ROOT=${STARROCKS_ROOT:-"/opt/starrocks"}
STARROCKS_HOME=${STARROCKS_ROOT}/cn
CN_CONFIG=$STARROCKS_HOME/conf/cn.conf

show_compute_nodes()
{
    local svc=$1
    sr_mysql $svc $FE_QUERY_PORT 'SHOW COMPUTE NODES;'
}

parse_confval_from_cn_conf()
{
    parse_confval_from_conf "$CN_CONFIG" "$1"
}

collect_env_info()
{
    # set MY_IP, MY_HOSTNAME and MY_SELF
    collect_host_info

    # heartbeat_port from conf file
    local heartbeat_port=`parse_confval_from_cn_conf "heartbeat_service_port"`
    if [[ "x$heartbeat_port" != "x" ]] ; then
        HEARTBEAT_PORT=$heartbeat_port
    fi
}

add_self()
{
    local svc=$1
    start=`date +%s`
    local timeout=$PROBE_TIMEOUT

    while true
    do
        log_stderr "Add myself ($MY_SELF:$HEARTBEAT_PORT) into FE ..."
        # if KUBE_STARROCKS_MULTI_WAREHOUSE environment variable is set, add compute node to the specified warehouse
        if  [[ "x$KUBE_STARROCKS_MULTI_WAREHOUSE" != "x" ]] ; then
            sr_mysql $svc $FE_QUERY_PORT "CREATE WAREHOUSE IF NOT EXISTS $KUBE_STARROCKS_MULTI_WAREHOUSE;"
            sr_mysql $svc $FE_QUERY_PORT \
              "ALTER SYSTEM ADD COMPUTE NODE \"$MY_SELF:$HEARTBEAT_PORT\" INTO WAREHOUSE $KUBE_STARROCKS_MULTI_WAREHOUSE;"
        else
            sr_mysql $svc $FE_QUERY_PORT "ALTER SYSTEM ADD COMPUTE NODE \"$MY_SELF:$HEARTBEAT_PORT\";"
        fi

        memlist=`show_compute_nodes $svc`
        if echo "$memlist" | grep -q -w "$MY_SELF" &>/dev/null ; then
            break;
        fi

        let "expire=start+timeout"
        now=`date +%s`
        if [[ $expire -le $now ]] ; then
            log_stderr "Time out, abort!"
            exit 1
        fi

        sleep $PROBE_INTERVAL

    done
}

drop_my_self()
{
    local svc=$1
    local start=`date +%s`
    local memlist=

    # If we infinitely retry to drop myself, it may cause the pod to be stuck in the Terminating state.
    for ((i=0;i<3;++i))
    do
        log_stderr "try to drop myself($MY_SELF) from FE ..."
        memlist=`show_compute_nodes $svc`
        ret=$?
        if [[ $ret -eq 0 ]] ; then
            # return code 0: no error
            selfinfo=`echo "$memlist" | grep -w "\<$MY_SELF\>" | awk '{printf("%s:%s\n", $2, $3);}'`
            if [[ "x$selfinfo" == "x" ]] ; then
                log_stderr "myself is not in fe cluster"
                return 0
            else
                log_stderr "drop my self $selfinfo ..."
                sr_mysql $svc $FE_QUERY_PORT "ALTER SYSTEM DROP COMPUTE NODE \"$selfinfo\";"
                break;
            fi
        else
            log_stderr "Got error $ret, sleep and retry ..."
            sleep $PROBE_INTERVAL
        fi
    done
}

svc_name=$1
if [[ "x$svc_name" == "x" ]] ; then
    echo "Need a required parameter!"
    echo "  Example: $0 <fe_service_name>"
    exit 1
fi

collect_env_info
add_self $svc_name || exit $?

log_stderr "run start_cn.sh"

addition_args=
if [[ "x$LOG_CONSOLE" == "x1" ]] ; then
    # env var `LOG_CONSOLE=1` can be added to enable logging to console
    addition_args="--logconsole"
fi
# replace the shell with the CN process, so that it receives the container stop signal directly
# and its exit status becomes the exit status of the container
exec $STARROCKS_HOME/bin/start_cn.sh $addition_args
