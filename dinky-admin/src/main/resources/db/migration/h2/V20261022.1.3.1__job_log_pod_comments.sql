-- 为已建立的历史日志索引表补充中文元数据注释。
-- 不修改已执行的建表迁移，避免改变其 Flyway 校验值；不存储日志正文。
COMMENT ON TABLE dinky_job_log_pod IS '程序执行的 Kubernetes JM/TM 日志位置索引，按租户、执行记录和 Pod UID 隔离';
COMMENT ON COLUMN dinky_job_log_pod.id IS '日志位置记录主键';
COMMENT ON COLUMN dinky_job_log_pod.tenant_id IS '租户编号，用于隔离不同租户的日志记录';
COMMENT ON COLUMN dinky_job_log_pod.job_instance_id IS '程序执行记录编号，关联 dinky_job_instance.id';
COMMENT ON COLUMN dinky_job_log_pod.flink_job_id IS 'Flink 作业编号，辅助定位本次执行';
COMMENT ON COLUMN dinky_job_log_pod.namespace IS 'Kubernetes 命名空间';
COMMENT ON COLUMN dinky_job_log_pod.application_id IS 'Kubernetes Application 实际 cluster-id，对应 Pod 的 app 标签';
COMMENT ON COLUMN dinky_job_log_pod.role IS '进程类型：JM 为 JobManager，TM 为 TaskManager';
COMMENT ON COLUMN dinky_job_log_pod.pod_name IS 'Pod 名称，Pod 重建时名称可能重复';
COMMENT ON COLUMN dinky_job_log_pod.pod_uid IS 'Kubernetes 原始 Pod UID，采用短横线形式，作为运行身份标识';
COMMENT ON COLUMN dinky_job_log_pod.node_name IS 'Pod 所在节点名称，对应 Agent 配置的键';
COMMENT ON COLUMN dinky_job_log_pod.host_ip IS 'Pod 所在物理机或虚拟机的节点 IP';
COMMENT ON COLUMN dinky_job_log_pod.relative_dir IS '相对日志根目录的实际目录，兼容 UID 下划线写法';
COMMENT ON COLUMN dinky_job_log_pod.host_log_dir IS '宿主机日志绝对目录，供页面展示和人工定位';
COMMENT ON COLUMN dinky_job_log_pod.files_json IS '最近成功读取的文件元数据 JSON，保存名称、字节大小和修改时间，不保存日志正文';
COMMENT ON COLUMN dinky_job_log_pod.first_seen IS '首次采集到该 Pod 的时间';
COMMENT ON COLUMN dinky_job_log_pod.last_seen IS '最后一次采集到该 Pod 的时间';
