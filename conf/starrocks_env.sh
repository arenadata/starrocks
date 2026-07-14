#!/usr/bin/env bash
# Optional overrides sourced by start_*/stop_* scripts. Everything is commented,
# so the scripts' built-in defaults stay in effect (dev runs unchanged). A value
# already in the environment (e.g. set by the systemd unit) wins regardless.

# export PID_DIR=${PID_DIR:-/run/starrocks}
# export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-arenadata-openjdk-21}
# export ADH_SERVICE_NAME=${ADH_SERVICE_NAME:-STARROCKS}

# Role overrides.
[ -e "$STARROCKS_HOME/conf/fe_env.sh" ] && source "$STARROCKS_HOME/conf/fe_env.sh"
[ -e "$STARROCKS_HOME/conf/be_env.sh" ] && source "$STARROCKS_HOME/conf/be_env.sh"
