-- Apply once with the content-processing feature disabled. Preserve epoch tombstones after deletion.
CREATE TABLE agent_media_authority_epoch (
    video_id BIGINT NOT NULL PRIMARY KEY,
    epoch BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_media_processing_grant (
    grant_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    tenant_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    video_id BIGINT NOT NULL,
    source_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    revocation_epoch BIGINT NOT NULL,
    purpose VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    KEY ix_media_grant_expiry (expires_at),
    KEY ix_media_grant_video (video_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

INSERT INTO agent_media_authority_epoch(video_id,epoch)
SELECT video_id,0 FROM video WHERE 1=1
ON DUPLICATE KEY UPDATE video_id=VALUES(video_id);

CREATE TRIGGER agent_media_epoch_insert AFTER INSERT ON video FOR EACH ROW
INSERT INTO agent_media_authority_epoch(video_id,epoch) VALUES(NEW.video_id,1)
ON DUPLICATE KEY UPDATE epoch=epoch+1;

CREATE TRIGGER agent_media_epoch_update AFTER UPDATE ON video FOR EACH ROW
INSERT INTO agent_media_authority_epoch(video_id,epoch)
SELECT NEW.video_id,1 WHERE NOT(OLD.status <=> NEW.status)
 OR NOT(OLD.is_delete <=> NEW.is_delete) OR NOT(OLD.file_url <=> NEW.file_url)
 OR NOT(OLD.duration <=> NEW.duration) OR NOT(OLD.user_id <=> NEW.user_id)
ON DUPLICATE KEY UPDATE epoch=epoch+1;

CREATE TRIGGER agent_media_epoch_delete AFTER DELETE ON video FOR EACH ROW
INSERT INTO agent_media_authority_epoch(video_id,epoch) VALUES(OLD.video_id,1)
ON DUPLICATE KEY UPDATE epoch=epoch+1;

-- Last step only: partial installation must never admit a processing grant.
CREATE TABLE agent_media_authority_schema (
    schema_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    ready BOOLEAN NOT NULL
) ENGINE=InnoDB;
INSERT INTO agent_media_authority_schema(schema_id,ready) VALUES('content-processing-v1',TRUE);
