#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
APP="${1:?需要指定与任务名称相同的 application ID}"
[[ "$APP" =~ ^[a-z0-9]([-a-z0-9]*[a-z0-9])?$ ]] || { echo '任务名称须为 Kubernetes DNS 名称' >&2; exit 1; }
need FLINK_STATE_PVC
cat <<YAML
apiVersion: v1
kind: Pod
metadata: {name: flink-pod-template}
spec:
  initContainers:
    - name: prepare-logs
      image: $FLINK_IMAGE
      imagePullPolicy: Never
      command: [/bin/sh, -ec]
      args: ['mkdir -p "/logs/\$APP_ID/\${POD_UID}_\${POD_NAME}"; chown 9999:9999 "/logs/\$APP_ID/\${POD_UID}_\${POD_NAME}"']
      securityContext: {runAsUser: 0}
      env:
        - {name: APP_ID, value: '$APP'}
        - name: POD_UID
          valueFrom: {fieldRef: {fieldPath: metadata.uid}}
        - name: POD_NAME
          valueFrom: {fieldRef: {fieldPath: metadata.name}}
      volumeMounts:
        - {name: logs, mountPath: /logs}
  containers:
    - name: flink-main-container
      env:
        - {name: APP_ID, value: '$APP'}
        - name: POD_UID
          valueFrom: {fieldRef: {fieldPath: metadata.uid}}
        - name: POD_NAME
          valueFrom: {fieldRef: {fieldPath: metadata.name}}
      volumeMounts:
        - {name: logs, mountPath: /opt/flink/log, subPathExpr: '\$(APP_ID)/\$(POD_UID)_\$(POD_NAME)'}
        - {name: state, mountPath: /opt/flink/state}
  volumes:
    - name: logs
      hostPath: {path: '$LOG_ROOT', type: DirectoryOrCreate}
    - name: state
      persistentVolumeClaim: {claimName: '$FLINK_STATE_PVC'}
YAML
