CREATE TABLE IF NOT EXISTS dinky_job_log_pod (
    id SERIAL PRIMARY KEY,
    tenant_id INTEGER NOT NULL,
    job_instance_id INTEGER NOT NULL,
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
    files_json TEXT,
    first_seen TIMESTAMP NOT NULL,
    last_seen TIMESTAMP NOT NULL,
    UNIQUE (tenant_id, job_instance_id, pod_uid)
);
