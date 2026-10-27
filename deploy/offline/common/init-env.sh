#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/.env}"
umask 077
if [[ ! -f "$ENV_FILE" ]]; then cp "$ROOT/.env.example" "$ENV_FILE"; fi
set -a; source "$ENV_FILE"; set +a
if [[ -z "${LOG_AGENT_TOKEN:-}" ]]; then
  token=$(openssl rand -hex 32)
  tmp=$(mktemp "${ENV_FILE}.XXXXXX")
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" == LOG_AGENT_TOKEN=* ]]; then printf "LOG_AGENT_TOKEN='%s'\n" "$token"; else printf '%s\n' "$line"; fi
  done < "$ENV_FILE" > "$tmp"
  mv "$tmp" "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"
echo "配置已准备: $ENV_FILE（未输出 Token；已有配置不会被默认值覆盖）"
echo '请编辑数据库等配置；Agent 与 Dinky 必须使用同一个 ENV_FILE。'
