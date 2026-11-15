CREATE TABLE agent_content_work (
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    work_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document JSON NOT NULL,
    grant_json JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL,
    fence BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    ready_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deadline_at DATETIME(6) NOT NULL,
    result_ref VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL,
    result_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    error_code VARCHAR(64) CHARACTER SET ascii NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_content_work_key (work_key),
    KEY ix_content_work_ready (status,ready_at),
    KEY ix_content_work_lease (status,lease_until),
    KEY ix_content_work_deadline (deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_content_subscription (
    tenant_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    owner_user_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    job_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id,owner_user_id,request_id),
    KEY ix_content_subscription_job (job_id,status),
    CONSTRAINT fk_content_subscription_job FOREIGN KEY (job_id)
        REFERENCES agent_content_work(job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
