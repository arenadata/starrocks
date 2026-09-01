#!/bin/bash

# Helpers shared by the FE/BE/CN container entrypoint scripts.
#
# The file is copied next to the *_entrypoint.sh scripts in the image and sourced by them, it must not
# start anything on its own.
#
# NOTE: the entrypoint scripts never write to $STARROCKS_HOME/conf. The configuration is expected to be
# mounted over that directory (ConfigMap/Secret volume, `docker run -v`), so the container can run with a
# read-only root filesystem.

# `mysql` writes its history file into $HOME, which is not writable in the images (the starrocks user is
# created with --no-create-home) and even less so with a read-only root filesystem.
export MYSQL_HISTFILE=${MYSQL_HISTFILE:-/dev/null}

# start_{fe,be,cn}.sh write <component>.pid into $PID_DIR, which defaults to the bin/ directory of the
# installation, i.e. into the image itself. /tmp is writable in every supported deployment, and both
# FE (createAndLockPidFile) and BE (starrocks_main.cpp) abort when the pid file cannot be created.
export PID_DIR=${PID_DIR:-/tmp}

# Time limits of the `mysql` calls below. `show frontends/backends/compute nodes` hangs as long as the
# cluster has no leader, so every call has to be bounded.
SR_MYSQL_TIMEOUT=${SR_MYSQL_TIMEOUT:-15}
SR_MYSQL_CONNECT_TIMEOUT=${SR_MYSQL_CONNECT_TIMEOUT:-2}

# TLS of the `mysql` calls below. The client shipped with the images negotiates TLS on its own as soon as
# the FE offers it (its default is --ssl-mode=PREFERRED), which is enough for a FE that refuses
# unencrypted connections (ssl_force_secure_transport), but it does not verify the certificate. Set
# SR_MYSQL_SSL_MODE=VERIFY_CA (or VERIFY_IDENTITY) together with SR_MYSQL_SSL_CA to have the node register
# itself over a verified connection.
SR_MYSQL_SSL_MODE=${SR_MYSQL_SSL_MODE:-}
SR_MYSQL_SSL_CA=${SR_MYSQL_SSL_CA:-}

log_stderr()
{
    echo "[`date`] $@" >&2
}

# parse_confval_from_conf <conf-file> <key>
# A naive helper to grep the given key from a StarRocks conf file.
# Assumes the conf format: ^\s*<key>\s*=\s*<value>\s*$
parse_confval_from_conf()
{
    local conffile=$1
    local confkey=$2
    local confvalue=
    if [[ -f "$conffile" ]] ; then
        # [[:space:]] instead of \s: the latter is a GNU extension and does not work with a BSD grep/sed
        confvalue=`grep "\<$confkey\>" $conffile | grep -v '^[[:space:]]*#' | \
            sed 's|^[[:space:]]*'$confkey'[[:space:]]*=[[:space:]]*\(.*\)[[:space:]]*$|\1|g'`
    else
        log_stderr "conf file $conffile does not exist, can not read '$confkey' from it"
    fi
    echo "$confvalue"
}

# collect_host_info
# Sets MY_IP, MY_HOSTNAME and MY_SELF. POD_IP/POD_FQDN are used when provided by the orchestrator,
# HOST_TYPE selects which of them the node registers itself with (FQDN, anything else means IP).
collect_host_info()
{
    if [[ "x$POD_IP" == "x" ]] ; then
        POD_IP=`hostname -i | awk '{print $1}'`
    fi

    if [[ "x$POD_FQDN" == "x" ]] ; then
        POD_FQDN=`hostname -f`
    fi

    MY_IP=$POD_IP
    MY_HOSTNAME=$POD_FQDN

    if [[ "x$HOST_TYPE" == "xFQDN" ]] ; then
        MY_SELF=$MY_HOSTNAME
    else
        MY_SELF=$MY_IP
    fi
}

# sr_mysql <host> <port> <sql>
# Runs a single statement against the FE query port as root.
sr_mysql()
{
    local host=$1
    local port=$2
    local sql=$3
    local ssl_opts=
    if [[ "x$SR_MYSQL_SSL_MODE" != "x" ]] ; then
        ssl_opts+=" --ssl-mode=$SR_MYSQL_SSL_MODE"
    fi
    if [[ "x$SR_MYSQL_SSL_CA" != "x" ]] ; then
        ssl_opts+=" --ssl-ca=$SR_MYSQL_SSL_CA"
    fi
    timeout $SR_MYSQL_TIMEOUT mysql --connect-timeout $SR_MYSQL_CONNECT_TIMEOUT $ssl_opts \
        -h $host -P $port -u root --skip-column-names --batch -e "$sql"
}
