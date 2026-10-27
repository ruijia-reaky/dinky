#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/lib.sh"
k get nodes -o wide
k -n "$AGENT_NAMESPACE" get ds,pods -o wide
k -n "$DINKY_NAMESPACE" get deploy,pod,svc,pvc -o wide
k -n "$FLINK_NAMESPACE" get pod,svc,pvc -o wide
k auth can-i list pods --as="system:serviceaccount:$DINKY_NAMESPACE:dinky" -n "$FLINK_NAMESPACE"
k auth can-i get nodes --as="system:serviceaccount:$DINKY_NAMESPACE:dinky"
echo '页面验证：新任务 RUNNING、checkpoint 递增、历史日志 JM/TM、下载正文、停止/重启后旧日志保留。'
