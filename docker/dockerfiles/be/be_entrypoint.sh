#!/bin/bash

# Notes:
# There're several ENV variables used in the BE entrypoint script:
# * COREDUMP_ENABLED: when it's set to true and BE process is crashed, a coredump is generated and the BE process would be restarted;
# * DEBUG_MODE: when it's set to true, BE process is restarted always;

HOST_TYPE=${HOST_TYPE:-"IP"}
FE_QUERY_PORT=${FE_QUERY_PORT:-9030}
PROBE_TIMEOUT=60
PROBE_INTERVAL=2
HEARTBEAT_PORT=9050
MY_SELF=
MY_IP=`hostname -i`
MY_HOSTNAME=`hostname -f`
STARROCKS_ROOT=${STARROCKS_ROOT:-"/opt/starrocks"}
export STARROCKS_HOME=${STARROCKS_ROOT}/be
BE_CONFIG=$STARROCKS_HOME/conf/be.conf


log_stderr()
{
    echo "[`date`] $@" >&2
}

# Debug mode supervisor (DEBUG_MODE / COREDUMP_ENABLED): the shell stays alive to restart BE after a crash.
# Signals sent to the container (tini forwards them only to this script) must then be forwarded to the
# BE child process explicitly, otherwise the shell dies and BE is SIGKILLed once the PID namespace goes away.
BE_PID=
STOP_SIGNAL_RECEIVED=

forward_signal_to_be()
{
    local sig=$1
    STOP_SIGNAL_RECEIVED=$sig
    if [[ "x$BE_PID" != "x" ]] && kill -0 $BE_PID 2>/dev/null ; then
        log_stderr "Got SIG$sig, forwarding it to starrocks_be pid $BE_PID ..."
        kill -s $sig $BE_PID
    else
        log_stderr "Got SIG$sig, no starrocks_be process to forward it to"
    fi
}

# run_be_supervised [args...]
# Runs start_be.sh as a child process, forwards SIGTERM/SIGINT to it and returns its exit status.
run_be_supervised()
{
    trap 'forward_signal_to_be TERM' TERM
    trap 'forward_signal_to_be INT' INT

    $STARROCKS_HOME/bin/start_be.sh "$@" &
    BE_PID=$!

    local ret
    while true
    do
        wait $BE_PID 2>/dev/null
        ret=$?
        if [[ $ret -gt 128 ]] ; then
            # `wait` returns early with a status > 128 when a trapped signal arrives.
            # Keep waiting while BE is alive; if it has exited meanwhile, collect its real status.
            if kill -0 $BE_PID 2>/dev/null ; then
                continue
            fi
            wait $BE_PID 2>/dev/null
            local late=$?
            if [[ $late -ne 127 ]] ; then
                ret=$late
            fi
        fi
        break
    done

    trap - TERM INT
    BE_PID=
    return $ret
}

# run_be_debug_loop [args...]
# Keeps restarting BE: always when DEBUG_MODE is true, after SIGABRT(134)/SIGSEGV(139) when COREDUMP_ENABLED is true
# (so that upload_coredump.sh can pick up the core file while the container is still alive).
run_be_debug_loop()
{
    while true; do
      run_be_supervised "$@"
      ret=$?
      if [[ "x$STOP_SIGNAL_RECEIVED" != "x" ]] ; then
          # the container is being stopped, never restart BE
          log_stderr "starrocks_be process exited with status $ret after SIG$STOP_SIGNAL_RECEIVED"
          exit $ret
      fi
      if [[ $ret -ne 0 && "x$LOG_CONSOLE" != "x1" ]] ; then
          nol=50
          log_stderr "Last $nol lines of be.INFO ..."
          tail -n $nol $STARROCKS_HOME/log/be.INFO
          log_stderr "Last $nol lines of be.out ..."
          tail -n $nol $STARROCKS_HOME/log/be.out
      fi

      should_exit=true
      if [[ "$DEBUG_MODE" == "true" ]]; then
        should_exit=false
      fi

      if [[ "$COREDUMP_ENABLED" == "true" && ($ret -eq 134 || $ret -eq 139) ]]; then
        should_exit=false
      fi

      if [[ "$should_exit" == "true" ]]; then
        exit  $ret
      fi

      # Print a message indicating the failure
      log_stderr "starrocks_be process exited with status: $ret"
      echo "Restarting starrocks_be ..."

      # Wait for a few seconds before restarting
      sleep_interval=${BE_RESTART_WAIT_SECONDS:-5}
      echo "wait for $sleep_interval seconds ..."
      sleep $sleep_interval
    done
}

update_conf_from_configmap()
{
    if [[ "x$CONFIGMAP_MOUNT_PATH" == "x" ]] ; then
        log_stderr 'Empty $CONFIGMAP_MOUNT_PATH env var, skip it!'
        return 0
    fi
    if ! test -d $CONFIGMAP_MOUNT_PATH ; then
        log_stderr "$CONFIGMAP_MOUNT_PATH not exist or not a directory, ignore ..."
        return 0
    fi
    local tgtconfdir=$STARROCKS_HOME/conf
    for conffile in `ls $CONFIGMAP_MOUNT_PATH`
    do
        log_stderr "Process conf file $conffile ..."
        local tgt=$tgtconfdir/$conffile
        if test -e $tgt ; then
            # make a backup
            mv -f $tgt ${tgt}.bak
        fi
        ln -sfT $CONFIGMAP_MOUNT_PATH/$conffile $tgt
    done
}

show_backends(){
    timeout 15 mysql --connect-timeout 2 -h $svc -P $FE_QUERY_PORT -u root --skip-column-names --batch -e 'SHOW BACKENDS;'
}

parse_confval_from_cn_conf()
{
    # a naive script to grep given confkey from cn conf file
    # assume conf format: ^\s*<key>\s*=\s*<value>\s*$
    local confkey=$1
    local confvalue=`grep "\<$confkey\>" $BE_CONFIG | grep -v '^\s*#' | sed 's|^\s*'$confkey'\s*=\s*\(.*\)\s*$|\1|g'`
    echo "$confvalue"
}

collect_env_info()
{
    # heartbeat_port from conf file
    local heartbeat_port=`parse_confval_from_cn_conf "heartbeat_service_port"`
    if [[ "x$heartbeat_port" != "x" ]] ; then
        HEARTBEAT_PORT=$heartbeat_port
    fi

    if [[ "x$HOST_TYPE" == "xIP" ]] ; then
        MY_SELF=$MY_IP
    else
        MY_SELF=$MY_HOSTNAME
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
        timeout 15 mysql --connect-timeout 2 -h $svc -P $FE_QUERY_PORT -u root --skip-column-names --batch -e "ALTER SYSTEM ADD BACKEND \"$MY_SELF:$HEARTBEAT_PORT\";"
        memlist=`show_backends $svc`
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

svc_name=$1
if [[ "x$svc_name" == "x" ]] ; then
    echo "Need a required parameter!"
    echo "  Example: $0 <fe_service_name>"
    exit 1
fi

update_conf_from_configmap
collect_env_info
add_self $svc_name || exit $?
log_stderr "run start_be.sh"

addition_args=
if [[ "x$LOG_CONSOLE" == "x1" ]] ; then
    # env var `LOG_CONSOLE=1` can be added to enable logging to console
    addition_args="--logconsole"
fi

if [[ "$DEBUG_MODE" != "true" && "$COREDUMP_ENABLED" != "true" ]] ; then
    # production mode: replace the shell with the BE process, so that it receives the container stop signal
    # directly and its exit status becomes the exit status of the container
    exec $STARROCKS_HOME/bin/start_be.sh $addition_args
fi

# debug mode: keep the shell alive as a supervisor that restarts BE, see run_be_debug_loop
log_stderr "DEBUG_MODE=$DEBUG_MODE COREDUMP_ENABLED=$COREDUMP_ENABLED, run starrocks_be under the restart loop"
if [[ "$COREDUMP_ENABLED" == "true" ]]; then
  # start inotifywait loop daemon to monitor core dump generation
  $STARROCKS_ROOT/upload_coredump.sh &
fi
run_be_debug_loop $addition_args
