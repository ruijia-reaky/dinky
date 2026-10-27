#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
[[ -f "$ENV_FILE" ]] || { echo "先运行 bash common/init-env.sh，然后编辑 $ENV_FILE" >&2; exit 1; }
set -a
source "$ENV_FILE"
set +a
need() { [[ -n "${!1:-}" ]] || { echo "缺少配置: $1" >&2; exit 1; }; }
for key in DINKY_NAMESPACE FLINK_NAMESPACE AGENT_NAMESPACE; do
  value="${!key}"
  [[ "$value" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ && ${#value} -le 63 ]] || { echo "$key 不是合法命名空间" >&2; exit 1; }
done
for key in AGENT_PORT AGENT_CONTAINER_PORT DINKY_PORT DINKY_NODE_PORT MYSQL_PORT; do
  value="${!key}"
  [[ "$value" =~ ^[0-9]+$ && "$value" -ge 1 && "$value" -le 65535 ]] || { echo "$key 端口无效" >&2; exit 1; }
done
[[ "$DINKY_NODE_PORT" -ge 30000 && "$DINKY_NODE_PORT" -le 32767 ]] || { echo 'NodePort 必须在默认范围 30000-32767 内' >&2; exit 1; }
[[ "$LOG_ROOT" =~ ^/[a-zA-Z0-9_./-]+$ && "$LOG_ROOT" != / ]] || { echo '日志根目录无效' >&2; exit 1; }
KUBECTL="${KUBECTL:-kubectl}"
k() { "$KUBECTL" "$@"; }
namespace() { k create namespace "$1" --dry-run=client -o yaml | k apply -f - >&2; }
token_check() { [[ ${#LOG_AGENT_TOKEN} -ge 32 ]] || { echo 'LOG_AGENT_TOKEN 至少 32 字符' >&2; exit 1; }; }
