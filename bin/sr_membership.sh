#!/usr/bin/env bash
#
# sr_membership.sh — simple StarRocks node membership tool.
#
# Registers/deregisters FE/CN nodes on the FE leader via SQL .
#
# Usage:
#   sr_membership.sh [options] add  follower|observer|compute-node <fqdn:port>
#   sr_membership.sh [options] drop follower|observer|compute-node <fqdn[:port]>
#
#   add:  port is required (edit_log_port for FE, heartbeat_service_port for CN
#         — the playbook knows them from the service config).
#   drop: port is optional — the node is looked up in SHOW FRONTENDS /
#         SHOW COMPUTE NODES by host and deregistered by its stored host:port
#         (StarRocks resolves FQDN->IP at ADD; DROP needs the stored form).
#
# Connection options (override env vars, which override defaults):
#   -H, --host HOST         FE host for SQL   (env SR_SQL_HOST,     default 127.0.0.1)
#   -P, --port PORT         FE MySQL port     (env SR_SQL_PORT,     default 9030)
#   -u, --user USER         service account   (env SR_SQL_USER,     default starrocks)
#   -p, --password PASS     account password  (env SR_SQL_PASSWORD, default empty)
#                           NOTE: visible in `ps`; prefer the env var for secrets.
#   -h, --help              show usage
#
# Output: one JSON line {"changed": bool, "msg": "..."}; exit 0 on success
# (including idempotent no-ops), exit 1 on error. For Ansible:
#   register: sr; changed_when: (sr.stdout | from_json).changed
#
set -u

SR_SQL_HOST="${SR_SQL_HOST:-127.0.0.1}"
SR_SQL_PORT="${SR_SQL_PORT:-9030}"
SR_SQL_USER="${SR_SQL_USER:-starrocks}"
SR_SQL_PASSWORD="${SR_SQL_PASSWORD:-}"

say() { # $1=changed, $2=msg, $3=exit code
  local m=${2//\\/\\\\}; m=${m//\"/\\\"}; m=${m//$'\n'/ }
  printf '{"changed": %s, "msg": "%s"}\n' "$1" "$m"
  exit "$3"
}

usage() {
  sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
}

# --- option parsing: flags override env/defaults; rest is positional ---
POSITIONAL=()
while [ $# -gt 0 ]; do
  case "$1" in
    -H|--host)       [ $# -ge 2 ] || say false "option '$1' requires a value" 1; SR_SQL_HOST=$2; shift 2 ;;
    --host=*)        SR_SQL_HOST=${1#*=}; shift ;;
    -P|--port)       [ $# -ge 2 ] || say false "option '$1' requires a value" 1; SR_SQL_PORT=$2; shift 2 ;;
    --port=*)        SR_SQL_PORT=${1#*=}; shift ;;
    -u|--user)       [ $# -ge 2 ] || say false "option '$1' requires a value" 1; SR_SQL_USER=$2; shift 2 ;;
    --user=*)        SR_SQL_USER=${1#*=}; shift ;;
    -p|--password)   [ $# -ge 2 ] || say false "option '$1' requires a value" 1; SR_SQL_PASSWORD=$2; shift 2 ;;
    --password=*)    SR_SQL_PASSWORD=${1#*=}; shift ;;
    -h|--help)       usage; exit 0 ;;
    --)              shift; break ;;
    -*)              say false "unknown option '$1' (see --help)" 1 ;;
    *)               POSITIONAL+=("$1"); shift ;;
  esac
done
set -- ${POSITIONAL[@]+"${POSITIONAL[@]}"}

sql() { # run SQL, echo output, return mysql rc
  local auth=(-u"$SR_SQL_USER")
  [ -n "$SR_SQL_PASSWORD" ] && auth+=(-p"$SR_SQL_PASSWORD")
  mysql -h"$SR_SQL_HOST" -P"$SR_SQL_PORT" "${auth[@]}" --connect-timeout=5 -BN -e "$1" 2>&1
}

[ $# -eq 3 ] || say false "usage: $0 [options] add|drop follower|observer|compute-node <fqdn[:port]> (see --help)" 1
action=$1; kind=$2; target=$3

# Node lookup strategy: SHOW output has a fixed column layout, and StarRocks
# always stores nodes by IP — so we match the IP column (and port column)
# exactly instead of scanning all fields.
case "$kind" in
  follower|observer) show="SHOW FRONTENDS";     kw=${kind^^}; ip_col=3; port_col=4 ;;
  compute-node)      show="SHOW COMPUTE NODES"; kw="COMPUTE NODE"; ip_col=2; port_col=3 ;;
  *)                 say false "unknown node kind '$kind'" 1 ;;
esac
case "$action" in
  add|drop) ;;
  *)        say false "unknown action '$action'" 1 ;;
esac

host=$target; port=""
[[ "$target" == *:* ]] && { host=${target%:*}; port=${target##*:}; }
[ "$action" = add ] && [ -z "$port" ] \
  && say false "add requires <fqdn:port> (edit_log_port for FE, heartbeat_service_port for CN)" 1

# StarRocks stores nodes by IP (FQDN is resolved at ADD) — normalize the input
# the same way. Fall back to the raw host if DNS resolution fails.
ip=$(getent ahostsv4 "$host" 2>/dev/null | awk 'NR==1{print $1}')
match=${ip:-$host}

rows=$(sql "$show"); rc=$?
[ $rc -eq 0 ] || say false "$show failed on $SR_SQL_HOST:$SR_SQL_PORT: $rows" 1

# Find stored host:port for this node by exact IP/port columns. If $port is
# given — match that exact port, else take the first match and reuse its port.
found=$(printf '%s\n' "$rows" | awk -F'\t' -v m="$match" -v p="$port" -v ic="$ip_col" -v pc="$port_col" '
  $ic == m && (p == "" || $pc == p) { print $ic, $pc; exit }')

if [ "$action" = add ]; then
  [ -n "$found" ] && say false "$host:$port already registered — no-op" 0
  out=$(sql "ALTER SYSTEM ADD $kw \"$host:$port\""); rc=$?
  [ $rc -eq 0 ] || say false "ALTER SYSTEM ADD $kw failed: $out" 1
  say true "$host:$port registered (ALTER SYSTEM ADD $kw)" 0
else
  [ -z "$found" ] && say false "$host${port:+:$port} is not registered — no-op" 0
  stored_h=${found% *}; stored_p=${found#* }
  out=$(sql "ALTER SYSTEM DROP $kw \"$stored_h:$stored_p\""); rc=$?
  [ $rc -eq 0 ] || say false "ALTER SYSTEM DROP $kw failed: $out" 1
  say true "$host deregistered (ALTER SYSTEM DROP $kw \"$stored_h:$stored_p\")" 0
fi
