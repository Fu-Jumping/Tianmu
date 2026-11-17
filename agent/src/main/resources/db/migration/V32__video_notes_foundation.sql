CREATE TABLE agent_video_notes_job (
 job_id CHAR(36) NOT NULL PRIMARY KEY, root_key CHAR(64) NOT NULL UNIQUE,
 tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL,
 request_json JSON NOT NULL, status VARCHAR(24) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 generation INT NOT NULL DEFAULT 1, plan_digest CHAR(64) NULL,
 budget_micros BIGINT NOT NULL, reserved_micros BIGINT NOT NULL DEFAULT 0,
 used_micros BIGINT NOT NULL DEFAULT 0, document_digest CHAR(64) NULL,
 deadline_at DATETIME(6) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX notes_owner (tenant_id,user_id), INDEX notes_scan (status,deadline_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_video_notes_binding (
 tenant_id VARCHAR(128) NOT NULL, user_id VARCHAR(128) NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
 request_digest CHAR(64) NOT NULL, job_id CHAR(36) NOT NULL,
 PRIMARY KEY(tenant_id,user_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_video_notes_dependency (
 root_id CHAR(36) NOT NULL, generation INT NOT NULL, unit_key VARCHAR(128) NOT NULL,
 unit_json JSON NOT NULL, work_id CHAR(36) NULL,
 PRIMARY KEY(root_id,generation,unit_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_video_notes_call (
 call_id CHAR(36) NOT NULL PRIMARY KEY, root_id CHAR(36) NOT NULL, generation INT NOT NULL,
 operation_key VARCHAR(128) NOT NULL, state VARCHAR(24) NOT NULL,
 reserved_micros BIGINT NOT NULL, result_ref VARCHAR(128) NULL, result_digest CHAR(64) NULL,
 actual_micros BIGINT NULL, UNIQUE KEY notes_call_operation(root_id,generation,operation_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
