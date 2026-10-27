#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
for key in FLINK_STATE_PVC FLINK_NFS_SERVER FLINK_NFS_PATH; do need "$key"; done
namespace "$FLINK_NAMESPACE"
cat <<YAML | k apply -f -
apiVersion: v1
kind: PersistentVolume
metadata: {name: '$FLINK_NAMESPACE-state'}
spec:
  capacity: {storage: '$FLINK_STATE_SIZE'}
  accessModes: [ReadWriteMany]
  persistentVolumeReclaimPolicy: Retain
  storageClassName: ''
  nfs: {server: '$FLINK_NFS_SERVER', path: '$FLINK_NFS_PATH'}
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: '$FLINK_STATE_PVC', namespace: '$FLINK_NAMESPACE'}
spec:
  accessModes: [ReadWriteMany]
  storageClassName: ''
  volumeName: '$FLINK_NAMESPACE-state'
  resources:
    requests: {storage: '$FLINK_STATE_SIZE'}
YAML
echo '这里只创建 PV/PVC，不安装 NFS、不创建服务端目录、不修改导出权限。服务端目录须允许 Flink UID/GID 9999 读写。'
