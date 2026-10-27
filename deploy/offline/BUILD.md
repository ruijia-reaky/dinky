# 从源码构建镜像、打包和使用离线部署包

本目录与当前分支配套，版本 **1.2.4**，Flink **1.18.1**。1.3.0-SNAPSHOT 是开发版，不是正式发布的1.3.0。部署使用方法见 [README](README.md)。这里只保存源码、配置模板和脚本，不保存镜像归档、真实 .env、kubeconfig、数据库备份或业务 JAR。

## 一、构建环境

构建机需要 Git、JDK11、Maven（或仓库 Maven wrapper）、项目要求的 Node/pnpm、Docker 和 Python3.8+、足够磁盘。构建阶段需要 Maven/npm依赖和基础镜像；离线构建需提前缓存这些资源。目标节点只需要手册中的部署工具，不需要 Python3。

建议预留至少20GB额外空间，按实际镜像和缓存增加。离线节点磁盘压力会导致 kubelet 回收未使用镜像；Never策略下需重新导入，不能依赖仓库自动补拉。

先检出本说明所在版本分支，记录 `git rev-parse HEAD`。不要把1.2.4和dev的编译产物混合。以下从仓库根目录执行，构建目录示例为 `$HOME/dinky-offline-build-1.2.4`。

## 二、编译发行包和 Application JAR

```bash
./mvnw clean package -Pprod,web,flink-1.18 -DskipTests
```

该命令用于生成构建产物，不代表测试通过；提交前另行执行选定单元测试。检查 `build/` 中的发行归档，包含 lib、config、html、extends 等；确认 html 已含最新前端。Application fat JAR 位于 `dinky-app/dinky-app-1.18/target/`，文件名包含 `jar-with-dependencies`。如果构建环境需要 Maven 镜像或 Node/pnpm 镜像，请配置本机受控 settings，不把账号写入仓库。

```bash
BUILD_DIR="$HOME/dinky-offline-build-1.2.4"
mkdir -p "$BUILD_DIR/dinky/release" "$BUILD_DIR/flink/runtime-libs" "$BUILD_DIR/flink/usrlib"
# 先 ls build/*.tar.gz，确认本版发行归档文件名，再填写实际文件；不要选择其他版本。
tar -xzf build/实际发行包.tar.gz --strip-components=1 -C "$BUILD_DIR/dinky/release"
cp deploy/docker/Dockerfile.history-logs "$BUILD_DIR/dinky/Dockerfile"
cp deploy/docker/start-history-logs.sh "$BUILD_DIR/dinky/"
```

检查干净的 release：不得从运行容器直接复制整个目录，因为里面可能已有密码、Token、kubeconfig、日志和业务数据。`config/application*.yml` 必须使用环境变量/占位配置，不含实际连接凭据。运行配置由部署时 Secret 注入。

发行包需带本版 dinky-client-1.18、OkHttp4.9.3、Okio2.8.0及Log4j2.19.0依赖。检查 `.env.example` 的 `DINKY_EXTRA_CLASSPATH` 每项确实存在于镜像。1.2.4的client路径为 `extends/flink1.18/dinky/`，dev模板路径为 `lib/`；发行布局不同时调整模板到真实位置。不能仅改版本字符串，忽略实际 JAR 版本。

基础镜像预先获取或从可信介质导入：

```bash
docker pull flink:1.18.1-scala_2.12-java11
docker pull python:3.12-slim
# Agent Dockerfile 的实际 FROM 以源文件为准，必要时提前导入相应基础镜像。
docker build --pull=false -t dinky-offline:1.2.4-history "$BUILD_DIR/dinky"
docker build --pull=false -t dinky-log-agent:1.2.4 deploy/log-agent
```

## 三、准备并构建可选 Flink 示例镜像

```bash
cp deploy/offline/flink/Dockerfile "$BUILD_DIR/flink/"
cp deploy/offline/flink/flink-run-logs.sh "$BUILD_DIR/flink/"
cp deploy/offline/flink/log4j-console.properties "$BUILD_DIR/flink/"
cp dinky-app/dinky-app-1.18/target/dinky-app-1.18-1.2.4-jar-with-dependencies.jar "$BUILD_DIR/flink/usrlib/dinky-app.jar"
```

从本机受控 Maven 仓库复制以下公开依赖至 `$BUILD_DIR/flink/runtime-libs/`：

- `mysql/mysql-connector-java/8.0.28/mysql-connector-java-8.0.28.jar`
- `org/apache/flink/flink-table-planner_2.12/1.18.1/flink-table-planner_2.12-1.18.1.jar`
- `org/scala-lang/scala-library/2.12.7/scala-library-2.12.7.jar`

Dockerfile会移除 planner-loader，使用完整planner，避免两者同时加载。上面的MySQL驱动是应用运行依赖，不含数据库账号。

编译无外部源/汇演示任务：

```bash
mkdir -p "$BUILD_DIR/demo-classes"
# 以已构建的Dinky镜像导出其中的Flink编译依赖，容器不需要启动。
cid=$(docker create dinky-offline:1.2.4-history)
docker cp "$cid:/opt/flink/lib" "$BUILD_DIR/flink-compile-lib"
docker rm "$cid"
javac -source 8 -target 8 -cp "$BUILD_DIR/flink-compile-lib/*" -d "$BUILD_DIR/demo-classes" deploy/offline/flink/Backport124LogJob.java
jar cf "$BUILD_DIR/flink/usrlib/offline-log-demo.jar" -C "$BUILD_DIR/demo-classes" .
docker build --pull=false -t flink-offline:1.18.1-dinky124-run-batches "$BUILD_DIR/flink"
```

演示类名带124是历史命名，两版复用演示源码，但 dinky-app.jar 必须各自匹配。演示任务一小时后结束。正式任务需自行准备业务JAR及依赖，不能把现场客户JAR打入通用包。

## 四、生成三份 ZIP

确认三个本地镜像存在并已检查内容：

```bash
docker image inspect dinky-log-agent:1.2.4 dinky-offline:1.2.4-history flink-offline:1.18.1-dinky124-run-batches
python3 deploy/offline/package.py --output "$HOME/dinky-offline-output-1.2.4"
cd "$HOME/dinky-offline-output-1.2.4"
sha256sum -c SHA256SUMS.txt
```

输出目录必须为空，脚本不覆盖旧包。脚本检查 linux/amd64，使用 docker save 流式gzip导出，逐包CRC验证，生成镜像和ZIP两层SHA256校验。包含01-log-agent、02-dinky-k8s、03-flink-run-batches，三包解压到相同根目录。打包脚本不会读取真实 .env；镜像自身的内容仍需构建者审核，它不会自动移除镜像层中的秘密。

使用自定义标签时可指定 `--agent-image`、`--dinky-image`、`--flink-image`。同时修改包模板 `.env.example` 的对应标签，确保导入的标签与部署引用一致；仅改运行环境的版本号不会产生新镜像。

打包采用明确文件白名单，不遍历真实 .env、generated 和 images 下的现场文件。成品ZIP/镜像不得提交Git。修改源码后应重新编译、构建镜像并生成新包，不要只重压旧镜像。

## 五、交付与使用顺序

1. 将三个ZIP和SHA256SUMS交付到内网控制机，先核对SHA256。
2. 按 [README](README.md) 解压，运行common/init-env.sh，编辑一份共用.env。数据库须独立，Agent/Dinky的Token一致。
3. 每个可能调度的节点导入三个组件所需镜像。K3s用k3s containerd，不是只docker load。
4. 先部署Agent（DaemonSet），再Dinky，最后配置Flink状态卷并提交演示任务。
5. 检查JM/TM日志下载、JM重建后旧日志、取消任务后历史下载；验收真实CDC需另做业务一致性验证。

升级已有环境前备份数据库与配置，并按相应版本迁移流程执行；这些包的全新部署验证不代替公司的现有库升级验证。不要让不同版本同时写同一个库。
