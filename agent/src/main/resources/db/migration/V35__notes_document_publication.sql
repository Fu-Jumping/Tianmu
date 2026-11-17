CREATE TABLE agent_video_notes_document (
 root_id CHAR(36) NOT NULL, generation INT NOT NULL,
 reference_json JSON NOT NULL, document_digest CHAR(64) NOT NULL,
 plan_digest CHAR(64) NOT NULL, source_snapshot_digest CHAR(64) NOT NULL,
 quality_policy_digest CHAR(64) NOT NULL, quality_json JSON NOT NULL,
 summary_work_id CHAR(36) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(root_id,generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
