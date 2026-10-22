# Dinky Application 历史日志

执行记录保存每个 JM/TM 的 Pod UID、节点和宿主机目录。页面展示文件名、大小、复制路径与下载入口。
下载从节点的只读文件服务流式传输，不依赖旧 Flink REST 服务或 Kubernetes Pod 的存在。

## 部署

1. 编译并部署包含本次修改的 Dinky。Flyway 自动建立 `dinky_job_log_pod`；MySQL、PostgreSQL 和 H2 脚本均已提供。
2. 每个可能运行 Flink Pod 的节点使用 Python 3 部署 `log_agent.py`；只有 Python 2 的旧机器可通过 Python 3 容器运行文件服务。
3. 配置 `LOG_AGENT_ROOT=/data/flink-logs`、`LOG_AGENT_BIND=<节点内网IP>`、`LOG_AGENT_PORT=18089` 和随机生成的 `LOG_AGENT_TOKEN`（至少 32 字符）。不要使用服务器或数据库密码作为 Agent Token。
4. 使用 `log-agent.service`，按系统修改 `User/Group`，通过专门组授予日志目录读取权限，避免 world-writable 权限。环境文件权限设为 0600。
5. 将 `application-job-logs.example.yml` 合并到 Dinky 配置中。agents 的 key 必须与 `Pod.spec.nodeName` 完全相同。只允许 Dinky 后端访问 Agent；跨信任网络应使用 HTTPS 或内部反向代理。
6. 核对当前 hostPath/subPathExpr。示例模板生成 `{app}/{uid}_{pod}` 目录；实际目录不同时调整 `directory-template`，不能把推算路径当作已经确认存在的文件。
7. 重新提交 Application 作业，打开执行记录的“历史日志”。配置开启前的执行记录不会自动补建。

Kubernetes 采集凭据沿用该次提交的 GatewayConfig/kubeconfig，至少需要指定命名空间的 `pods get/list` 权限。
多个 Kubernetes 集群存在同名节点时，需为不同 Dinky 实例配置独立 Agent 映射；当前映射按节点名称配置，尚不支持同一实例中同名节点的跨集群路由。

### Application Operator 的日志保留配置

Operator 模式同样使用上述索引和节点 Agent。需要确认 Operator 生成的 `log4j-console.properties` 使用追加写入并保留轮转文件，参考 [Operator Helm 日志覆盖示例](operator-values.example.yaml)。Operator 1.9.0 的测试默认配置存在 `appender.rolling.append=false`：实际测试中，取消和删除资源后可能只保留约 1 KB 退出日志。仅验证下载接口返回成功不能证明完整运行正文仍保留。

在 Operator Helm values 中追加 `appender.rolling.append=true` 和启动轮转策略，按原有发布流程更新并确认控制器读取到新配置，再重新提交 Application。不要把已有日志覆盖掉后再期待本功能恢复正文。其他版本先核对实际 appender 名称和日志配置，保留现有权限、网络及 webhook 设置。

已验证 Operator 1.9.0 + Flink 1.18.1、单节点 ClusterIP：通过 Dinky 提交，JM/TM 建立历史索引，取消并删除 FlinkDeployment/Pod 后，运行期间保存的日志原文仍完整包含在下载结果中。NodePort 与多物理节点不在该次验收范围。

## 数据与权限

- 一次执行与一个 Pod UID 对应一条索引；Pod 重建会增加记录，不覆盖旧 UID。
- 定时采集保存 Pod 身份与可读取的文件清单；页面刷新时重新扫描，发现轮转文件。
- 宿主机目录和文件名分别保存，数据库只存元数据，不存日志正文。
- 同一次 Application 的所有 JM/TM Pod 分别保存，按 Pod UID 区分；不会仅取第一个 TM，也不以当前是否分配到任务过滤。采集周期内已经存在的空闲 TM 同样可以记录。
- UID 从 Kubernetes API 获取，保留标准短横线形式。目录不存在时会尝试该 UID 的下划线形式，找到后保存实际目录；只转换已知 UID，不转换 Pod 名称中的短横线。已有短横线目录优先，不合并两个目录的文件。
- HTTP 接口校验登录、当前租户、所属执行记录及已索引文件名；不接受客户端提供节点 URL 或任意绝对路径。
- 文件服务限制根目录、拒绝路径穿越和符号链接，下载使用固定长度分块读取。
- 文件服务不可达时保留上次文件清单，页面显示不可达；没有配置 Agent 时仍显示路径。
- 日志轮转后若旧文件已删除，下载返回 410。节点或原盘不可用时，节点下载也不可用。
- Dinky 自动清理执行历史时同时清理索引，不删除节点日志文件；文件保留周期由运维单独配置。

## 验证

运行 `python3 test_log_agent.py` 验证鉴权、文件下载、大小、路径穿越、符号链接、轮转文件与文件缺失。
这些独立测试不等价于真实 Kubernetes/Flink CDC 端到端验证。

端到端验收步骤：

1. 以 Kubernetes Application 模式提交实际 Flink CDC JAR，等待 JM/TM 索引及日志文件出现。
2. 下载文件并计算 SHA-256，与节点原文件比较。
3. 让作业进入 FAILED；删除该次 Application 的全部 Pod，确认旧文件仍可下载。
4. 复用同一应用名重新提交，检查新 Pod UID 不会覆盖或混入旧执行记录。
5. 重启 Dinky，确认旧索引可从数据库恢复并继续下载。
6. 停止 Agent、删除测试日志或模拟跨租户请求，确认页面状态和访问控制符合预期。

已完成 Flink 1.18.1 单节点 Kubernetes Application 端到端验证：作业失败和 Pod 自动删除后下载、Dinky 重启后下载、同名应用多次执行隔离、1 JM + 2 TM、真实空闲 TM，以及 UID 下划线目录回退。下载与宿主机文件做内容校验，浏览器实际保存由人工确认通过，自动化下载完成事件未捕获。Java 服务测试 7 项通过，Linux Agent 测试 7 项通过，前端生产构建通过。MySQL、PostgreSQL 16、H2 的建表及中文注释 SQL 均已执行验证；尚未验证跨物理节点调度。

## 源码入口与迁移

| 范围 | 入口 | 作用 |
| --- | --- | --- |
| 作业监控 | `dinky-admin/.../job/handler/JobRefreshHandler.java` | 在终态处理前异步采集 Pod 元数据 |
| 采集与下载 | `dinky-admin/.../service/JobLogService.java` | 使用实际 `kubernetes.cluster-id` 查询全部 JM/TM，保存索引并代理节点文件流 |
| 配置 | `JobLogProperties.java`、`JobLogExecutorConfig.java` | 功能开关、目录规则、节点 Agent 和独立有界线程池 |
| 接口 | `JobLogController.java` | 查询执行日志文件和下载，沿用登录与租户校验 |
| 数据 | `JobLogPod.java`、`JobLogPodMapper.java`、`MybatisPlusConfig.java` | 日志索引模型、数据库访问和租户隔离 |
| 清理 | `ClearJobHistoryHandler.java` | 清理执行记录时同步清理索引，不删除节点文件 |
| 页面 | `JobHistoryList.tsx`、`HistoryLogs.tsx` | 每次执行的历史日志入口、列表、路径复制及下载链接 |
| 配置页修复 | `ProFormFlinkConfig.tsx`、`DataStudio/model.tsx` | Flink 配置选项为空时避免调用未定义对象的 flatMap |
| 节点服务 | 本目录 `log_agent.py` | 只读列举和流式下载日志，独立于 Pod 生命周期 |

`...` 表示 `src/main/java/org/dinky`。完整文件名以 Git 变更清单为准。

三种数据库都提供 `V20261022.1.3.0__job_log_pod.sql` 建表和 `V20261022.1.3.1__job_log_pod_comments.sql` 中文表/字段注释迁移。后者单独追加，避免修改已经执行的建表迁移校验值。唯一键为 `(tenant_id, job_instance_id, pod_uid)`。

沿用项目现有 Flyway 配置进行增量升级，不要手工写入迁移历史或删除历史校验记录。MySQL/PostgreSQL 的 `R20261022.1.3.0__job_log_pod.sql` 是项目既有命名约定下的人工回退参考，会删除本功能索引；正常升级不要执行。原始宿主机日志不受回退 SQL 影响。

## 范围与后续

当前采集接入已有作业刷新任务，使用独立、有界线程池。Pod 若在两个采集周期之间创建并销毁，可能漏掉。
本次实现适合持续运行的 CDC Application；需要覆盖瞬时崩溃时，应增加持久化 Pod Watch/节点元数据补偿，而不能宣称定时采集绝不漏记。

目录规则本身以 Pod UID 隔离；同一 Pod 内的容器重启沿用目录，保留程度取决于 Flink 的日志轮转配置。
本功能展示关联进程日志，不会将多个作业混写的日志切分成单独作业日志。

当前方案部署成本较低，满足 Pod 删除后从原节点下载。生产要求包括节点/磁盘故障后也能下载时，建议节点 Agent 持续归档到现有 S3/HDFS 等集中存储，并保存归档对象键与上传状态；不要仅在作业结束时上传，也不要把日志文件放入 MySQL。
