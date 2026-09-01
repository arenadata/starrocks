#!/bin/bash

# $prog <fe-svc-name>

ENTRYPOINT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$ENTRYPOINT_DIR/entrypoint_common.sh"

# Editlog port: default 9010
EDIT_LOG_PORT=9010
# Query port: default 9030
QUERY_PORT=9030
# host_type, default "IP"
HOST_TYPE=${HOST_TYPE:-"IP"}
# FE leader
FE_LEADER=
# probe interval: 2 seconds
PROBE_INTERVAL=${PROBE_INTERVAL:-2}
# timeout for probe leader: 120 seconds
PROBE_LEADER_POD0_TIMEOUT=${PROBE_LEADER_POD0_TIMEOUT:-30} # at most 15 attempts, no less than the times needed for an election
PROBE_LEADER_PODX_TIMEOUT=${PROBE_LEADER_PODX_TIMEOUT:-120} # at most 60 attempts
# timeout for adding myself as a follower to the leader: 30 seconds
ADD_SELF_TIMEOUT=${ADD_SELF_TIMEOUT:-30}

# myself as IP or FQDN
MY_SELF=

STARROCKS_ROOT=${STARROCKS_ROOT:-"/opt/starrocks"}
STARROCKS_HOME=${STARROCKS_ROOT}/fe
FE_CONFFILE=$STARROCKS_HOME/conf/fe.conf
META_DIR=$STARROCKS_HOME/meta

parse_confval_from_fe_conf()
{
    parse_confval_from_conf "$FE_CONFFILE" "$1"
}

collect_env_info()
{
    # set MY_IP, MY_HOSTNAME, MY_SELF, POD_INDEX, EDIT_LOG_PORT, QUERY_PORT
    collect_host_info

    # example: fe-sr-deploy-1.fe-svc.kc-sr.svc.cluster.local
    POD_INDEX=`echo $MY_HOSTNAME | awk -F'.' '{print $1}' | awk -F'-' '{print $NF}'`

    # edit_log_port from conf file
    local edit_log_port=`parse_confval_from_fe_conf "edit_log_port"`
    if [[ "x$edit_log_port" != "x" ]] ; then
        EDIT_LOG_PORT=$edit_log_port
    fi

    # query_port from conf file
    local query_port=`parse_confval_from_fe_conf "query_port"`
    if [[ "x$query_port" != "x" ]] ; then
        QUERY_PORT=$query_port
    fi
}

show_frontends()
{
    local svc=$1
    # "show frontends" query will hang when there is no leader yet in the cluster, sr_mysql bounds it in time
    sr_mysql $svc $QUERY_PORT 'show frontends;'
}

probe_leader_for_pod0()
{
    # possible to have no result at all, because myself is the first FE instance in the cluster
    local svc=$1
    local start=`date +%s`
    local has_member=false
    local memlist=
    while true
    do
        NC="nc -z -w 2"
        if $NC $svc $QUERY_PORT ; then
            log_stderr "FE service is alive, check if has leader ..."

            memlist=`show_frontends $svc`
            local leader=`echo "$memlist" | grep '\<LEADER\>' | awk '{print $3}'`
            if [[ "x$leader" != "x" ]] ; then
                # has leader, done
                log_stderr "Find leader: $leader!"
                FE_LEADER=$leader
                return 0
            fi

            if [[ "x$memlist" != "x" ]] ; then
                # FE service does not have leader yet, but has members.
                has_member=true
            fi
            log_stderr "No leader yet, has_member: $has_member ..."
        else
            log_stderr "FE service $svc:$QUERY_PORT is not alive yet!"
        fi

        # no leader yet, check if needs timeout and quit
        local timeout=$PROBE_LEADER_POD0_TIMEOUT
        if $has_member ; then
            # set timeout to the same as PODX since there are other members
            timeout=$PROBE_LEADER_PODX_TIMEOUT
        fi

        local now=`date +%s`
        let "expire=start+timeout"
        if [[ $expire -le $now ]] ; then
            if $has_member ; then
                log_stderr "Timed out, abort!"
                exit 1
            else
                log_stderr "Timed out, no members detected ever, assume myself is the first node .."
                # empty FE_LEADER
                FE_LEADER=""
                return 0
            fi
        fi
        sleep $PROBE_INTERVAL
    done
}

probe_leader_for_podX()
{
    # wait until find a leader or timeout
    local svc=$1
    local start=`date +%s`
    while true
    do
        NC="nc -z -w 2"
        if $NC $svc $QUERY_PORT ; then
            log_stderr "FE service is alive, check if has leader ..."
            local leader=`show_frontends $svc | grep '\<LEADER\>' | awk '{print $3}'`
            if [[ "x$leader" != "x" ]] ; then
                # has leader, done
                log_stderr "Find leader: $leader!"
                FE_LEADER=$leader
                return 0
            fi
            # no leader yet, check if needs timeout and quit
            log_stderr "No leader yet ..."
        else
            log_stderr "FE service $svc:$QUERY_PORT is not alive yet!"
        fi

        local now=`date +%s`
        let "expire=start+PROBE_LEADER_PODX_TIMEOUT"
        if [[ $expire -le $now ]] ; then
            log_stderr "Timed out, abort!"
            exit 1
        fi

        sleep $PROBE_INTERVAL
    done
}

probe_leader()
{
    local svc=$1
    # find leader under current service and set to FE_LEADER
    if [[ "$POD_INDEX" -eq 0 ]] ; then
        probe_leader_for_pod0 $svc
    else
        probe_leader_for_podX $svc
    fi
}

start_fe_no_meta()
{
    # apply --host_type and --helper option
    local svc=$1
    local opts=""
    if [[ "x$HOST_TYPE" != "x" ]] ; then
        opts+=" --host_type $HOST_TYPE"
    fi

    if [[ "x$FE_LEADER" != "x" ]] ; then
        opts+=" --helper $FE_LEADER:$EDIT_LOG_PORT"

        local start=`date +%s`
        while true
        do
            log_stderr "Add myself($MY_SELF:$EDIT_LOG_PORT) to leader as follower ..."
            sr_mysql $FE_LEADER $QUERY_PORT "ALTER SYSTEM ADD FOLLOWER \"$MY_SELF:$EDIT_LOG_PORT\";"
            # check if added successful
            if show_frontends $svc | grep -q -w "$MY_SELF" &>/dev/null ; then
                break;
            fi

            local now=`date +%s`
            let "expire=start+ADD_SELF_TIMEOUT"
            if [[ $expire -le $now ]] ; then
                log_stderr "Timed out, abort!"
                exit 1
            fi

            log_stderr "Sleep a while and retry adding ..."
            sleep $PROBE_INTERVAL
        done
    fi

    if [[ "x$LOG_CONSOLE" == "x1" ]] ; then
        opts+=" --logconsole"
    fi
    log_stderr "first start with no meta run start_fe.sh with additional options: '$opts'"
    # replace the shell with the FE process, so that it receives the container stop signal directly
    exec $STARROCKS_HOME/bin/start_fe.sh $opts
}

start_fe_with_meta()
{
    local opts=""
    if [[ "x$HOST_TYPE" != "x" ]] ; then
        opts+=" --host_type $HOST_TYPE"
    fi

    if [[ "x$LOG_CONSOLE" == "x1" ]] ; then
        opts+=" --logconsole"
    fi
    log_stderr "start with meta run start_fe.sh with additional options: '$opts'"
    # replace the shell with the FE process, so that it receives the container stop signal directly
    exec $STARROCKS_HOME/bin/start_fe.sh $opts
}

svc_name=$1
if [[ "x$svc_name" == "x" ]] ; then
    echo "Need a required parameter!"
    echo "  Example: $0 <fe_service_name>"
    exit 1
fi

# meta_dir from conf file
meta_dir=`parse_confval_from_fe_conf "meta_dir"`
if [[ "x$meta_dir" != "x" ]] ; then
    META_DIR=$meta_dir
fi

if [[ -f "$META_DIR/image/ROLE" ]];then
    log_stderr "start fe with exist meta."
    start_fe_with_meta
else
    log_stderr "first start fe with meta not exist."
    collect_env_info
    probe_leader $svc_name
    start_fe_no_meta $svc_name
fi
