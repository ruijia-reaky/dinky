#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
token_check
namespace "$AGENT_NAMESPACE"
umask 077
secret=$(mktemp);trap 'rm -f "$secret"' EXIT
printf 'LOG_AGENT_TOKEN=%s\n' "$LOG_AGENT_TOKEN" > "$secret"
k -n "$AGENT_NAMESPACE" create secret generic log-agent-token --from-env-file="$secret" --dry-run=client -o yaml | k apply -f -
bash "$ROOT/agent/render.sh" | k apply -f -
k -n "$AGENT_NAMESPACE" rollout status daemonset/dinky-log-agent --timeout=180s
k -n "$AGENT_NAMESPACE" get pods -o wide
