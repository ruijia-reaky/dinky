CREATE TABLE IF NOT EXISTS dinky_job_log_pod (
    id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id INT NOT NULL,
    job_instance_id INT NOT NULL,
    flink_job_id VARCHAR(64),
    namespace VARCHAR(253) NOT NULL,
    application_id VARCHAR(253) NOT NULL,
    role VARCHAR(8) NOT NULL,
    pod_name VARCHAR(253) NOT NULL,
    pod_uid VARCHAR(64) NOT NULL,
    node_name VARCHAR(253) NOT NULL,
    host_ip VARCHAR(64),
    relative_dir VARCHAR(1024) NOT NULL,
    host_log_dir VARCHAR(2048) NOT NULL,
    files_json MEDIUMTEXT,
    first_seen DATETIME NOT NULL,
    last_seen DATETIME NOT NULL,
    UNIQUE KEY uk_job_log_pod (tenant_id, job_instance_id, pod_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
