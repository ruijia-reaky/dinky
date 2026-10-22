# Dinky Java 11 container with historical logs

Build `Dockerfile.history-logs` with a complete customized distribution under `release/` and `start-history-logs.sh` in the build context. Do not include passwords, kubeconfig files or log Agent tokens in that directory. The image includes the Flink 1.18.1 Java 11 runtime libraries; it is separate from the Flink Application image and the Python log Agent image.

```sh
docker build -f Dockerfile.history-logs -t registry.example.com/team/dinky:history-v1 .
docker push registry.example.com/team/dinky:history-v1
```

Run Java in the foreground. Set `TZ=Asia/Shanghai` and, where appropriate, `JAVA_TOOL_OPTIONS=-Xms512m -Xmx2g -Duser.timezone=Asia/Shanghai`. Mount the additional configuration at `/run/dinky/application-job-logs.yml`. Persist Dinky logs, local resource-center data and any uploaded files outside the container writable layer. Database and Agent credentials belong in a protected environment file or a secret manager.

Kubernetes configuration, Flink configuration, Pod templates and JAR paths saved in the database must exist inside the container. Preserve those absolute paths with mounts or migrate the saved configuration. The Dinky container must reach MySQL, Kubernetes API, Flink REST and each recorded node's Agent endpoint. Linux host networking is an option; it does not require Docker socket access or a privileged container.

Stop the previous Dinky process before starting the container against the same database. Preserve a rollback image and database backup. Verify login, static resources, configuration options, task submission, monitoring, historical log downloads and container recreation.

`DINKY_EXTRA_CLASSPATH` optionally prepends explicit dependencies when a verified distribution needs them. It is not a substitute for reconciling conflicting libraries. Do not blindly reuse another release's dependency versions. The validation runtime used explicit Log4j, OkHttp/Okio and Dinky Flink client entries because its pre-existing test distribution contained conflicting fat JAR dependencies.

## Private registry

Use TLS and authentication. Configure the registry CA in each K3s node's `registries.yaml`; use a namespace-local `kubernetes.io/dockerconfigjson` Secret through `imagePullSecrets`. Do not disable TLS verification. The Agent DaemonSet must reference the private image; preloading an image alone does not validate registry authentication. Verify unauthenticated registry requests fail and both nodes report successful authenticated pulls. Pin production images by digest.

## Operator upgrades, recovery and terminal state

Operator SQL ConfigMaps use the enclosing FlinkDeployment as owner, so replacement of the generated Deployment does not remove the SQL. Native Application resources retain Deployment ownership. When live REST is unavailable, Dinky accepts only explicit FAILED, CANCELED or FINISHED evidence from the Operator for the same Flink job ID. Missing resources, unknown states or a reused application name with a different job ID do not establish a terminal state. An already lost terminal event cannot be reconstructed with certainty.

Matching Operator RUNNING/recovery states also keep the execution under monitoring during a long upgrade. When the generated ClusterIP/NodePort REST Service changes, Dinky refreshes the saved endpoint for that execution's cluster. An old UNKNOWN execution can be rechecked with the existing forced refresh action; the update must be based on live or matching Operator evidence, never a manual database status edit. LoadBalancer endpoint migration is not inferred by this fallback.

Cross-node checkpoint recovery requires shared checkpoint and HA storage, stable operator IDs and compatible application dependencies. With Kubernetes HA, Operator `last-state` upgrades can restore from a checkpoint on a different node. Check REST recovery metadata, including `is_savepoint=false`, actual Pod node assignments, subsequent checkpoints and new source records reaching the sink. This does not prove abrupt node failure recovery or shared-storage durability. An NFS server on one node remains a single point of failure.

Oracle JDBC must be available to the Flink runtime classloader (for example, in the Application image's `/opt/flink/lib/`), including when the Source enumerator is recreated. A driver available only to a temporary submission classloader may fail after HA recovery.
