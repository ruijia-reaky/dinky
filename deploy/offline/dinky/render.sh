#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
cat <<YAML
apiVersion: v1
kind: PersistentVolumeClaim
metadata: {name: dinky-data, namespace: $DINKY_NAMESPACE}
spec:
  accessModes: [ReadWriteOnce]
YAML
if [[ -n "$DINKY_STORAGE_CLASS" ]]; then printf '  storageClassName: "%s"\n' "$DINKY_STORAGE_CLASS"; fi
cat <<YAML
  resources:
    requests: {storage: '$DINKY_STORAGE_SIZE'}
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: dinky, namespace: $DINKY_NAMESPACE}
spec:
  replicas: 1
  strategy: {type: Recreate}
  selector:
    matchLabels: {app: dinky-offline}
  template:
    metadata:
      labels: {app: dinky-offline}
    spec:
      serviceAccountName: dinky
      securityContext: {fsGroup: 9999}
      initContainers:
        - name: prepare-data
          image: $DINKY_IMAGE
          imagePullPolicy: Never
          command: [/bin/sh, -ec]
          args: ['mkdir -p /workspace/logs /workspace/tmp /workspace/customJar /workspace/data; chown -R 9999:9999 /workspace']
          securityContext: {runAsUser: 0}
          volumeMounts:
            - {name: data, mountPath: /workspace}
      containers:
        - name: dinky
          image: $DINKY_IMAGE
          imagePullPolicy: Never
          securityContext: {runAsUser: 9999, runAsGroup: 9999, allowPrivilegeEscalation: false}
          args: ['--server.port=$DINKY_PORT', '--server.address=0.0.0.0', '--spring.config.additional-location=file:/run/dinky/application-job-logs.yml']
          envFrom:
            - secretRef: {name: dinky-runtime}
          env:
            - {name: TZ, value: Asia/Shanghai}
            - {name: DB_ACTIVE, value: mysql}
            - {name: DINKY_PORT, value: '$DINKY_PORT'}
            - {name: DINKY_TOKEN_NAME, value: '$DINKY_TOKEN_NAME'}
            - {name: AGENT_PORT, value: '$AGENT_PORT'}
            - {name: LOG_ROOT, value: '$LOG_ROOT'}
            - {name: JAVA_TOOL_OPTIONS, value: '$DINKY_JAVA_OPTIONS'}
            - {name: DINKY_EXTRA_CLASSPATH, value: '$DINKY_EXTRA_CLASSPATH'}
          ports:
            - {name: http, containerPort: $DINKY_PORT}
          startupProbe:
            tcpSocket: {port: http}
            periodSeconds: 5
            failureThreshold: 60
          readinessProbe:
            tcpSocket: {port: http}
            periodSeconds: 5
          livenessProbe:
            tcpSocket: {port: http}
            periodSeconds: 15
          resources:
            requests: {cpu: 250m, memory: 768Mi}
            limits: {cpu: '2', memory: 2Gi}
          volumeMounts:
            - {name: config, mountPath: /run/dinky, readOnly: true}
            - {name: data, mountPath: /opt/dinky/logs, subPath: logs}
            - {name: data, mountPath: /opt/dinky/tmp, subPath: tmp}
            - {name: data, mountPath: /opt/dinky/customJar, subPath: customJar}
            - {name: data, mountPath: /opt/dinky/data, subPath: data}
      volumes:
        - name: config
          configMap: {name: dinky-log-config}
        - name: data
          persistentVolumeClaim: {claimName: dinky-data}
---
apiVersion: v1
kind: Service
metadata: {name: dinky, namespace: $DINKY_NAMESPACE}
spec:
  type: NodePort
  selector: {app: dinky-offline}
  ports:
    - {name: http, port: $DINKY_PORT, targetPort: http, nodePort: $DINKY_NODE_PORT}
YAML
