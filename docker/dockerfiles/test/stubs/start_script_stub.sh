#!/bin/bash
# Stands in for start_fe.sh / start_be.sh / start_cn.sh: records how it was called and exits with a status
# chosen by the test ($START_STUB_RC, or $START_STUB_RC_<n> for the n-th call).

count=$(cat "$START_STUB_COUNT" 2>/dev/null || echo 0)
count=$((count + 1))
echo "$count" > "$START_STUB_COUNT"

{
    echo "call: $count"
    echo "args: $*"
    echo "PID_DIR=$PID_DIR"
    echo "MYSQL_HISTFILE=$MYSQL_HISTFILE"
    echo "STARROCKS_HOME=$STARROCKS_HOME"
    echo "PPID_CMD=$(ps -o comm= -p $PPID 2>/dev/null)"
} >> "$START_RECORD"

rc_var="START_STUB_RC_$count"
rc=${!rc_var}
exit ${rc:-${START_STUB_RC:-0}}
