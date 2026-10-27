#!/usr/bin/env bash
set -euo pipefail
component="${1:?用法: bash common/load-images.sh agent|dinky|flink [k3s|containerd|docker]}"
runtime="${2:-auto}"
ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
[[ "$component" == agent || "$component" == dinky || "$component" == flink ]] || exit 2
dir="$ROOT/$component/images"
(cd "$dir" && sha256sum -c SHA256SUMS)
if [[ "$runtime" == auto ]]; then
 if command -v k3s >/dev/null; then runtime=k3s; elif command -v ctr >/dev/null; then runtime=containerd; elif command -v docker >/dev/null; then runtime=docker; else echo '未找到受支持的镜像运行时' >&2; exit 1; fi
fi
for archive in "$dir"/*.tar.gz; do
 case "$runtime" in
  k3s) k3s ctr images import "$archive" ;;
  containerd) ctr -n k8s.io images import "$archive" ;;
  docker) docker load -i "$archive" ;;
  *) echo '运行时只支持 k3s/containerd/docker' >&2; exit 2 ;;
 esac
done
echo "导入完成: $runtime。Kubernetes 使用 containerd 时，只导入 Docker 不生效；所有可能调度节点都要导入。"
