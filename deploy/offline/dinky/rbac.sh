#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
namespace "$DINKY_NAMESPACE"
namespace "$FLINK_NAMESPACE"
cat <<YAML
apiVersion: v1
kind: ServiceAccount
metadata: {name: dinky, namespace: $DINKY_NAMESPACE}
---
apiVersion: v1
kind: ServiceAccount
metadata: {name: flink, namespace: $FLINK_NAMESPACE}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: {name: flink-application-manager, namespace: $FLINK_NAMESPACE}
rules:
  - apiGroups: ['']
    resources: [pods, services, configmaps, endpoints, events]
    verbs: [get, list, watch, create, update, patch, delete]
  - apiGroups: ['apps']
    resources: [deployments, replicasets]
    verbs: [get, list, watch, create, update, patch, delete]
  - apiGroups: ['flink.apache.org']
    resources: [flinkdeployments]
    verbs: [get, list, watch, create, update, patch, delete]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: {name: flink-application-manager, namespace: $FLINK_NAMESPACE}
roleRef: {apiGroup: rbac.authorization.k8s.io, kind: Role, name: flink-application-manager}
subjects:
  - {kind: ServiceAccount, name: dinky, namespace: $DINKY_NAMESPACE}
  - {kind: ServiceAccount, name: flink, namespace: $FLINK_NAMESPACE}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata: {name: '$DINKY_NAMESPACE-log-node-reader'}
rules:
  - apiGroups: ['']
    resources: [nodes]
    verbs: [get]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata: {name: '$DINKY_NAMESPACE-log-node-reader'}
roleRef: {apiGroup: rbac.authorization.k8s.io, kind: ClusterRole, name: '$DINKY_NAMESPACE-log-node-reader'}
subjects:
  - {kind: ServiceAccount, name: dinky, namespace: $DINKY_NAMESPACE}
YAML
