-- Apply while notes processing is disabled; existing VIDEO_CONTENT_PREPARATION grants remain unchanged.
CREATE TABLE IF NOT EXISTS agent_notes_grant_owner (
    grant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    user_id BIGINT NOT NULL,
    purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_notes_grant_owner (tenant_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
