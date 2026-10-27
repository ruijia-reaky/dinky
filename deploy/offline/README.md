# Dinky 1.3.0-SNAPSHOT 离线 Kubernetes 部署手册

这套附件提供 Agent、Dinky 和可选 Flink 三份 ZIP。解压到同一目录后，三个组件共享根目录一份 `.env`。镜像为 linux/amd64（x86_64）；ARM 不能直接加载运行。1.3.0-SNAPSHOT 是当前 dev 功能分支构建，不是正式发布的 1.3.0。

## 一、准备条件与边界

1. 已有可用 Kubernetes/K3s，所有目标节点 Ready；具备 kubectl 管理权限。本包不安装 Kubernetes、CNI、MySQL、NFS 或 Operator 控制器，不是操作系统离线安装包。
2. 目标节点有足够磁盘；控制机有 Bash、unzip、gzip、sha256sum、openssl、kubectl；K3s 使用自带 containerd。无需 Maven、Node、宿主机 Python 或每节点 Docker。
3. 已有 MySQL 和独立数据库、限定该库权限的账户。新环境请创建新库；不要把两版 Dinky 同时指向同一数据库。数据库密码放入真实 .env，ZIP 不包含现有环境凭据。
4. Dinky 的 PVC 需要集群已有默认 StorageClass，或在 .env 指定 DINKY_STORAGE_CLASS。Flink HA/checkpoint 需要已有可跨节点访问的 RWX 存储；请勿把不同节点的本地目录当作共享存储。
5. Kubernetes 系统镜像、Pod sandbox、存储 provisioner/helper 等应由集群管理员预先备齐。本包业务工作负载全部采用 imagePullPolicy: Never，缺镜像会直接报 ErrImageNeverPull，不会悄悄联网拉取。
6. 同一节点的 Agent hostPort 不能被旧 Agent 占用。切换前停止旧部署，并保留数据库及配置备份。不要删除宿主机 /data/flink-logs。

## 二、解压与共用 .env

将本版三个 ZIP 放到同一目录，依次运行（文件名中的版号以收到的文件为准）：

```bash
unzip -n Dinky-1.3.0-SNAPSHOT-01-log-agent.zip
unzip -n Dinky-1.3.0-SNAPSHOT-02-dinky-k8s.zip
unzip -n Dinky-1.3.0-SNAPSHOT-03-flink-run-batches.zip
cd dinky-offline-1.3.0-SNAPSHOT
bash common/init-env.sh
nano .env
```

`unzip -n` 不覆盖已经修改的文件。首次 init-env 从 .env.example 复制配置并用 openssl 生成随机 Agent Token，文件权限为 600；不会显示 Token。已有 .env 不会被默认值覆盖。纳入密码的文件不要上传仓库或聊天，不要打包给其他人。

必须填写 MYSQL_HOST、MYSQL_DATABASE、MYSQL_USERNAME、MYSQL_PASSWORD；核对命名空间和端口。Bash .env 中密码使用单引号，例如 `MYSQL_PASSWORD='change_me@123'`，@ 不需要转义；密码中如果包含单引号，须按 Bash 规则处理。此文件由本地可信脚本 source，不要使用来路不明的 .env。

|变量|含义|
|---|---|
|DINKY_VERSION / DINKY_IMAGE|本版程序和实际镜像引用。修改版本字符串不会变出另一版镜像，须导入匹配的镜像|
|AGENT_VERSION / AGENT_IMAGE|Agent 镜像标签；Agent 不是官方 Dinky 单独发布组件，标签表示交付兼容版本|
|AGENT_PORT|每台宿主机对 Dinky 提供的 Agent 端口，默认 18090|
|AGENT_CONTAINER_PORT|Agent 容器监听端口，默认 18089；探针与 LOG_AGENT_PORT 同步渲染|
|DINKY_TOKEN_NAME|同一 IP 不同端口同时运行两版时使用不同 Cookie 名，防止互相顶掉登录|
|DINKY_PORT|Dinky 容器及 Service 端口，默认 8888|
|DINKY_NODE_PORT|浏览器使用的 NodePort，默认 30888；默认 Kubernetes 范围 30000～32767|
|LOG_ROOT|节点日志根目录，也是页面显示路径及 Agent 只读挂载路径，默认 /data/flink-logs|
|LOG_AGENT_TOKEN|Agent 与 Dinky 必须相同；不要使用 SSH/MySQL 密码代替|
|DINKY_NAMESPACE / FLINK_NAMESPACE / AGENT_NAMESPACE|分别是管理服务、Flink 作业和 Agent 的命名空间|
|DINKY_STORAGE_CLASS / DINKY_STORAGE_SIZE|Dinky 工作目录 PVC，空 class 使用集群默认类；默认 5Gi|
|FLINK_STATE_PVC|Flink 命名空间里已存在的 RWX PVC 名称，默认 flink-state|
|FLINK_HA_ENABLED|生成作业配置时是否启用 Kubernetes HA，默认 true|
|FLINK_NFS_SERVER / FLINK_NFS_PATH|可选 NFS PV/PVC 示例使用的服务端及已有导出目录；不是 Agent 日志目录|

两个服务默认读取同一份 `.env`；也可以在每条命令前指定 `ENV_FILE=/绝对路径/common.env`。变更数据库、Token 或配置后重新 apply，并按第九节重启对应 Pod。两个版本若共用一个 Agent DaemonSet，必须使用同一个 Token；不能让两套不同 Token 的安装相互覆盖 Agent Secret。

## 三、在每个节点导入镜像

将对应 ZIP 解压到每个可能运行 Pod 的节点。在每个节点的根目录执行：

```bash
sudo bash common/load-images.sh agent k3s
sudo bash common/load-images.sh dinky k3s
sudo bash common/load-images.sh flink k3s
sudo k3s ctr images ls
sudo install -d -m 0755 /data/flink-logs
```

公司已有普通 containerd 时用 `containerd` 替换 `k3s`，脚本导入 k8s.io 命名空间。使用 Docker 仅用于查看/重打标签时传 docker；Kubernetes 若使用 containerd，仍必须导入 containerd。SHA256SUMS 不匹配时停止使用并重新传输。加载脚本不会自动 SSH 到其他节点。

若 .env 改了 LOG_ROOT，最后一条命令相应改为该路径。不要对已有整个日志树递归 chmod 或删除。Agent UID 65532、附加组 9999；Flink UID/GID 9999，日志目录需可遍历、日志文件需可读。

## 四、启动 Agent

在有 kubectl 权限的控制机执行：

```bash
bash agent/render.sh > agent-rendered.yaml
kubectl apply --dry-run=client -f agent-rendered.yaml
bash agent/apply.sh
```

脚本创建命名空间和 Token Secret，部署 DaemonSet，等待就绪。检查 Pod 数量与符合选择器的 Linux 节点数一致。Dinky 必须能访问每个节点 IP 的 AGENT_PORT；防火墙只允许需要的来源。/healthz 无认证且不暴露文件信息，目录与下载接口需要 Token。镜像中有 Python，不需要宿主机安装。

## 五、启动 Dinky

先由 DBA 创建独立库和账户，并允许 Kubernetes Pod 网络访问 MySQL。只对新空库首次初始化；已有公司数据库先备份并做独立升级验证。不要对现有数据库执行初始化 DROP 脚本。

```bash
bash dinky/rbac.sh > rbac-rendered.yaml
bash dinky/render.sh > dinky-rendered.yaml
kubectl apply --dry-run=client -f rbac-rendered.yaml
kubectl apply --dry-run=client -f dinky-rendered.yaml
bash dinky/apply.sh
```

Dinky 作为一个 Deployment 运行，服务账号为 dinky；Flink 账号为 flink。授权限定在指定 Flink 命名空间，另有 nodes get 用于旧日志记录的 IP 补查；不挂 Docker socket，也不用集群管理员 kubeconfig。RBAC 脚本需由管理员执行。

浏览器访问 `http://节点IP:30888/`（以 .env NodePort 为准）。首次进入按欢迎向导设置 admin 密码和系统配置。Dinky 对外地址应能被 Flink Pod 访问，例如 `http://dinky.$DINKY_NAMESPACE.svc.cluster.local:$DINKY_PORT`；不要填 127.0.0.1。实际创建库后由 Flyway 初始化/升级，失败时先查看日志和迁移记录，不要改 Flyway 历史掩盖错误。

```bash
source .env
kubectl -n "$DINKY_NAMESPACE" get pod,svc,pvc -o wide
kubectl -n "$DINKY_NAMESPACE" logs deploy/dinky -c dinky --tail=80
```

Dinky 的日志、tmp、自定义 JAR、data 位于 PVC，Pod 重建保留。RWO local-path 类不保证节点丢失后的自动跨节点可用，生产应选公司的持久化存储。MySQL 和节点 Flink 日志是独立的数据保留位置。

## 六、准备 Flink 状态存储

已有 RWX PVC 时直接把名称填入 FLINK_STATE_PVC，跳过 NFS 示例。使用已有 NFS 时填入服务器和已导出目录，然后：

```bash
bash flink/create-nfs-pvc.sh
source .env
kubectl -n "$FLINK_NAMESPACE" get pvc
```

该脚本只创建 PV/PVC，不安装 NFS、不修改服务器导出权限。路径必须先由存储管理员准备，允许 UID/GID 9999 读写。PV 回收策略 Retain，不会在卸载业务时自动清空共享数据。HA 元数据和 checkpoint 均写入这个共享卷；它不能替代存储层自身的高可用。

## 七、配置并提交一个演示任务

```bash
bash flink/generate-job-config.sh offline-log-demo-130
```

生成目录包含 cluster-config.json、pod-template.yaml 和 demo.sql。**任务名必须和生成参数一致**，否则日志目录 app 部分与采集规则不匹配。

在 Dinky 注册中心的集群配置中创建 Kubernetes Application 配置：

- Flink 配置目录：/opt/flink/conf；依赖目录：/opt/flink/lib；版本 1.18。
- 镜像：.env 中的 FLINK_IMAGE；命名空间：FLINK_NAMESPACE；账号 flink；REST ClusterIP；拉取策略 Never。
- 应用启动 JAR：local:///opt/flink/usrlib/dinky-app.jar；入口 org.dinky.app.MainApp。
- Pod 模板使用生成的 pod-template.yaml。Dinky 在集群内运行，可使用其服务账号自动认证（生成 JSON 的 kubeConfig 留空）。如公司页面强制要求显式 kubeconfig，应提供限定权限的配置，不要塞集群管理员证书。
- 其余 Flink 参数以 cluster-config.json 内 configuration 为准，包含 HA 与共享 checkpoint 目录。JSON 是接口配置数据，可供管理员通过对应版本 API 导入，不是 kubectl YAML。

在数据开发新建 FlinkJar 任务，名称 offline-log-demo-130，运行模式 Kubernetes Application，选择上面的集群配置，将 demo.sql 内容粘贴到编辑器，保存、提交。

演示作业无外部源/汇，不访问 Oracle/Kafka，不修改业务数据；持续约一小时、每 15 秒 checkpoint 并输出日志。看到 RUNNING 后检查历史日志的 JM/TM。若更换成公司 CDC JAR，须把 JAR、连接器及数据库驱动加入自己的 Flink 镜像，并重新构建/导出；本包不包含公司的 1355 业务 JAR和真实连接配置。

某些新 Kubernetes /version 字段会触发 Flink 1.18.1 的旧客户端探测兼容问题；“配置测试”失败不等于提交一定失败。按公司 Kubernetes 版本单独验证，不能据本包默认跳过实际提交。

## 八、验证日志与停止后下载

```bash
bash common/check.sh
```

页面检查：任务 RUNNING；checkpoint 持续递增；JM/TM 均有节点、目录和日志文件；下载正文包含本任务的演示标记。作业达到终态或 Pod 重建后，仍应能从原节点读取保留日志。文件依赖节点磁盘，节点损坏或目录被清理后 Agent 不会恢复文件。

run-batches 包装器每次 JVM 启动生成独立 UTC 时间 + UUID 文件前缀；20MB 轮转在该批次内计数，保留 5 个轮转文件。它是 Flink 镜像功能，不是另一个常驻 Agent。

主列表沿用独立 Job ID / 状态列，手动刷新；实时 Application 与旧执行对照在历史日志窗口。未知旧终态不会因当前出现新 ID 而被覆盖成 RUNNING。

## 九、修改配置和重启

修改端口、镜像后重跑 render 查看，再执行相应 apply。仅更新 Secret 中 Token/数据库密码不会自动更新已经运行进程的环境变量；两边统一 Token 后执行：

```bash
source .env
kubectl -n "$AGENT_NAMESPACE" rollout restart daemonset/dinky-log-agent
kubectl -n "$DINKY_NAMESPACE" rollout restart deployment/dinky
```

换 Agent 端口须同时更新 Dinky；换 LOG_ROOT 须同时更新 Flink Pod 模板并用于新提交，旧路径不会自动搬家。换版本不等于改字符串，必须导入匹配版本完整镜像、使用对应数据库升级流程及测试。

## 十、常见问题

|现象|检查|
|---|---|
|ErrImageNeverPull|该节点的 Kubernetes containerd 是否导入正确标签；仅 docker load 不够|
|Agent Pending/端口冲突|旧 DaemonSet 是否仍占 hostPort；污点和节点选择器是否匹配|
|Agent 403/日志不可读|Token 是否一致；节点目录 UID/GID/遍历权限；不是给目录设 777|
|Dinky PVC Pending|默认 StorageClass、容量、provisioner/helper 离线镜像是否齐备|
|Dinky 连接 MySQL 失败|地址不要 localhost；Pod 网络连通、账户授权、库名及密码|
|JM/TM 挂载失败|RWX PVC 同命名空间、NFS 客户端工具、网络及服务端目录权限|
|页面有路径却没文件|推算路径不保证目录已创建；查看初始化容器状态和实际节点目录|
|旧记录 UNKNOWN|缺少原 Job ID 的明确终态证据；不会自动用另一个 Job ID 覆盖|

停止作业请先在 Dinky 正常取消，再停止 Dinky Deployment。停止 Agent 会影响历史日志下载。删除命名空间/PVC 前先备份，本文不提供自动清盘或删除数据库命令。

## 文件与安全

镜像归档已 gzip 压缩，ZIP 中按原样存储以减少二次压缩时间。每个 images 目录有 SHA256SUMS。包内不含实际 .env、SSH/MySQL 密码、Agent Token、kubeconfig 或原数据库备份。使用内部 HTTP Agent 时应限制网络来源；跨不可信网络请使用公司 TLS 方案。

## 从源码制作这些离线包

仓库维护者请阅读 [构建与打包说明](BUILD.md)。`package.py` 从本机已构建镜像导出归档，生成三份 ZIP 和 SHA256SUMS；构建机需要 Docker/Python3，部署节点不需要 Python3。
