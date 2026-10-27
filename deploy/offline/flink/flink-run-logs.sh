#!/usr/bin/env bash
# One unique log prefix for every JVM start, including restarts in the same Pod.
set -euo pipefail
stamp=$(date -u +%Y%m%dT%H%M%S%3NZ)
run_uuid=$(cat /proc/sys/kernel/random/uuid)
export FLINK_IDENT_STRING="run-${stamp}-${run_uuid}"
exec "$(dirname "$0")/flink-console-original.sh" "$@"
