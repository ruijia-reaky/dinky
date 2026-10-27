#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
for key in MYSQL_HOST MYSQL_DATABASE MYSQL_USERNAME MYSQL_PASSWORD; do need "$key"; done
token_check
bash "$ROOT/dinky/rbac.sh" | k apply -f -
umask 077
secret=$(mktemp);trap 'rm -f "$secret"' EXIT
printf 'MYSQL_ADDR=%s:%s\nMYSQL_DATABASE=%s\nMYSQL_USERNAME=%s\nMYSQL_PASSWORD=%s\nLOG_AGENT_TOKEN=%s\n' "$MYSQL_HOST" "$MYSQL_PORT" "$MYSQL_DATABASE" "$MYSQL_USERNAME" "$MYSQL_PASSWORD" "$LOG_AGENT_TOKEN" > "$secret"
k -n "$DINKY_NAMESPACE" create secret generic dinky-runtime --from-env-file="$secret" --dry-run=client -o yaml | k apply -f -
k -n "$DINKY_NAMESPACE" create configmap dinky-log-config --from-file=application-job-logs.yml="$ROOT/dinky/application-job-logs.yml" --dry-run=client -o yaml | k apply -f -
bash "$ROOT/dinky/render.sh" | k apply -f -
k -n "$DINKY_NAMESPACE" rollout status deployment/dinky --timeout=300s
k -n "$DINKY_NAMESPACE" get pod,svc,pvc -o wide
echo "访问: http://任一可达节点IP:$DINKY_NODE_PORT/"
echo '修改 Secret 或 ConfigMap 后需主动 rollout restart deployment/dinky，不会默默重启已有服务。'
