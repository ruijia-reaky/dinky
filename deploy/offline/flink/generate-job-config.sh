#!/usr/bin/env bash
set -euo pipefail
source "$(dirname -- "$0")/../common/lib.sh"
APP="${1:?需要与 Dinky 任务名称一致的 application ID}"
umask 077
mkdir -p "$ROOT/generated/$APP"
bash "$ROOT/flink/pod-template.sh" "$APP" > "$ROOT/generated/$APP/pod-template.yaml"
POD=$(k create configmap serialized-template --from-file=podTemplate="$ROOT/generated/$APP/pod-template.yaml" --dry-run=client -o go-template='{{printf "%q" .data.podTemplate}}')
HA=''
if [[ "$FLINK_HA_ENABLED" == true ]]; then
 HA="\"high-availability.type\":\"kubernetes\",\"high-availability.storageDir\":\"file:///opt/flink/state/ha/$APP\","
fi
OPTIONS=$(cat <<JSON
{"kubernetes.namespace":"$FLINK_NAMESPACE","kubernetes.cluster-id":"$APP","kubernetes.service-account":"flink","kubernetes.jobmanager.service-account":"flink","kubernetes.container.image":"$FLINK_IMAGE","kubernetes.container.image.pull-policy":"Never","kubernetes.rest-service.exposed.type":"ClusterIP","jobmanager.memory.process.size":"1024m","taskmanager.memory.process.size":"1024m","taskmanager.numberOfTaskSlots":"1","parallelism.default":"$FLINK_PARALLELISM","classloader.resolve-order":"parent-first",$HA"state.checkpoints.dir":"file:///opt/flink/state/checkpoints/$APP"}
JSON
)
cat <<JSON > "$ROOT/generated/$APP/cluster-config.json"
{"type":"KUBERNETES_APPLICATION","clusterConfig":{"flinkConfigPath":"/opt/flink/conf","flinkLibPath":"/opt/flink/lib"},"flinkConfig":{"configuration":$OPTIONS,"flinkConfigList":[],"flinkVersion":"v1_18"},"kubernetesConfig":{"configuration":$OPTIONS,"kubeConfig":"","podTemplate":$POD},"appConfig":{"userJarPath":"local:///opt/flink/usrlib/dinky-app.jar","userJarMainAppClass":"org.dinky.app.MainApp","userJarParas":[]}}
JSON
cat > "$ROOT/generated/$APP/demo.sql" <<'SQL'
EXECUTE JAR WITH ('uri'='file:///opt/flink/usrlib/offline-log-demo.jar','main-class'='Backport124LogJob','args'='');
SQL
echo "已生成 $ROOT/generated/$APP/；不要把生成配置当成自动提交，需在 Dinky 配置中心和数据开发页面录入。"
