ALTER TABLE agent_content_work
 ADD COLUMN tenant_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(document,'$.asset.tenantId'))) STORED,
 ADD COLUMN video_key BIGINT GENERATED ALWAYS AS (CAST(JSON_UNQUOTE(JSON_EXTRACT(document,'$.asset.videoId')) AS SIGNED)) STORED,
 ADD COLUMN source_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(document,'$.asset.sourceVersion'))) STORED,
 ADD COLUMN domain_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(document,'$.asset.authorizationDomain'))) STORED,
 ADD COLUMN epoch_key BIGINT GENERATED ALWAYS AS (CAST(JSON_UNQUOTE(JSON_EXTRACT(document,'$.asset.revocationEpoch')) AS SIGNED)) STORED,
 ADD COLUMN stage_key VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(document,'$.stage'))) STORED,
 ADD COLUMN version_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (JSON_UNQUOTE(JSON_EXTRACT(document,'$.stageVersion'))) STORED,
 ADD COLUMN start_key BIGINT GENERATED ALWAYS AS (CAST(JSON_UNQUOTE(JSON_EXTRACT(document,'$.startMs')) AS SIGNED)) STORED,
 ADD COLUMN end_key BIGINT GENERATED ALWAYS AS (CAST(JSON_UNQUOTE(JSON_EXTRACT(document,'$.endMs')) AS SIGNED)) STORED,
 ADD INDEX ix_content_transcript_lookup(tenant_key,video_key,source_key,domain_key,epoch_key,stage_key,version_key,status,start_key);
