-- 为已建立的历史日志索引表补充中文元数据注释。
-- 不修改已执行的建表迁移，避免改变其 Flyway 校验值；不存储日志正文。
ALTER TABLE dinky_job_log_pod
    MODIFY COLUMN id INT NOT NULL AUTO_INCREMENT COMMENT '日志位置记录主键',
    MODIFY COLUMN tenant_id INT NOT NULL COMMENT '租户编号，用于隔离不同租户的日志记录',
    MODIFY COLUMN job_instance_id INT NOT NULL COMMENT '程序执行记录编号，关联 dinky_job_instance.id',
    MODIFY COLUMN flink_job_id VARCHAR(64) COMMENT 'Flink 作业编号，辅助定位本次执行',
    MODIFY COLUMN namespace VARCHAR(253) NOT NULL COMMENT 'Kubernetes 命名空间',
    MODIFY COLUMN application_id VARCHAR(253) NOT NULL COMMENT 'Kubernetes Application 实际 cluster-id，对应 Pod 的 app 标签',
    MODIFY COLUMN role VARCHAR(8) NOT NULL COMMENT '进程类型：JM 为 JobManager，TM 为 TaskManager',
    MODIFY COLUMN pod_name VARCHAR(253) NOT NULL COMMENT 'Pod 名称，Pod 重建时名称可能重复',
    MODIFY COLUMN pod_uid VARCHAR(64) NOT NULL COMMENT 'Kubernetes 原始 Pod UID，采用短横线形式，作为运行身份标识',
    MODIFY COLUMN node_name VARCHAR(253) NOT NULL COMMENT 'Pod 所在节点名称，对应 Agent 配置的键',
    MODIFY COLUMN host_ip VARCHAR(64) COMMENT 'Pod 所在物理机或虚拟机的节点 IP',
    MODIFY COLUMN relative_dir VARCHAR(1024) NOT NULL COMMENT '相对日志根目录的实际目录，兼容 UID 下划线写法',
    MODIFY COLUMN host_log_dir VARCHAR(2048) NOT NULL COMMENT '宿主机日志绝对目录，供页面展示和人工定位',
    MODIFY COLUMN files_json MEDIUMTEXT COMMENT '最近成功读取的文件元数据 JSON，保存名称、字节大小和修改时间，不保存日志正文',
    MODIFY COLUMN first_seen DATETIME NOT NULL COMMENT '首次采集到该 Pod 的时间',
    MODIFY COLUMN last_seen DATETIME NOT NULL COMMENT '最后一次采集到该 Pod 的时间',
    COMMENT = '程序执行的 Kubernetes JM/TM 日志位置索引，按租户、执行记录和 Pod UID 隔离';
