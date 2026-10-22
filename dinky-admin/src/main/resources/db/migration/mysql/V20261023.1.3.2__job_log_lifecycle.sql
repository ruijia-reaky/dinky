-- 保存 Pod 生命周期快照，区分当前运行、容器重启和历史 Pod；不根据文件存在推断失败。
ALTER TABLE dinky_job_log_pod ADD COLUMN lifecycle_json MEDIUMTEXT COMMENT 'Pod 存在性、阶段及容器重启/退出证据 JSON，旧记录为空表示未知';
