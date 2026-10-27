#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
cat <<YAML
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: dinky-log-agent
  namespace: $AGENT_NAMESPACE
spec:
  selector:
    matchLabels: {app: dinky-log-agent-offline}
  template:
    metadata:
      labels: {app: dinky-log-agent-offline}
    spec:
      automountServiceAccountToken: false
      nodeSelector: {kubernetes.io/os: linux}
      tolerations:
        - {key: node-role.kubernetes.io/control-plane, operator: Exists, effect: NoSchedule}
        - {key: node-role.kubernetes.io/master, operator: Exists, effect: NoSchedule}
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
        runAsGroup: 65532
        supplementalGroups: [9999]
        seccompProfile: {type: RuntimeDefault}
      containers:
        - name: agent
          image: $AGENT_IMAGE
          imagePullPolicy: Never
          env:
            - {name: LOG_AGENT_BIND, value: '0.0.0.0'}
            - {name: LOG_AGENT_PORT, value: '$AGENT_CONTAINER_PORT'}
            - {name: LOG_AGENT_ROOT, value: '$LOG_ROOT'}
            - name: LOG_AGENT_TOKEN
              valueFrom:
                secretKeyRef: {name: log-agent-token, key: LOG_AGENT_TOKEN}
          ports:
            - {name: http, containerPort: $AGENT_CONTAINER_PORT, hostPort: $AGENT_PORT, protocol: TCP}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: {drop: [ALL]}
          readinessProbe:
            httpGet: {path: /healthz, port: http}
            periodSeconds: 5
          livenessProbe:
            httpGet: {path: /healthz, port: http}
            initialDelaySeconds: 10
          resources:
            requests: {cpu: 20m, memory: 32Mi}
            limits: {cpu: 200m, memory: 128Mi}
          volumeMounts:
            - {name: logs, mountPath: '$LOG_ROOT', readOnly: true}
      volumes:
        - name: logs
          hostPath: {path: '$LOG_ROOT', type: DirectoryOrCreate}
YAML
