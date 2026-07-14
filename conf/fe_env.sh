#!/usr/bin/env bash
# FE-only overrides, sourced from starrocks_env.sh.

# HA join. Needed only on the first boot of a node with empty meta; ignored
# once local meta exists, and leader failover is automatic afterwards. Point
# it at the seed/leader, never at self (self is rejected). Unset on the
# seed / single FE.
# export HELPER=${HELPER:-<leader>:9010}
