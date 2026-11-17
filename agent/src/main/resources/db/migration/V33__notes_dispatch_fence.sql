ALTER TABLE agent_video_notes_call
 ADD COLUMN work_id CHAR(36) NULL,
 ADD COLUMN work_fence BIGINT NULL,
 ADD COLUMN work_owner VARCHAR(128) NULL,
 ADD COLUMN dispatched_at DATETIME(6) NULL,
 ADD INDEX notes_dispatch_recovery(state,work_id);
