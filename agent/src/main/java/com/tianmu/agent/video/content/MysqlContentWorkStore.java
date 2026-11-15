package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.video.content.ContentWork.*;

/** Durable CAS scheduler. Explicit assembly only: no worker or endpoint is enabled by this class. */
public final class MysqlContentWorkStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public MysqlContentWorkStore(DataSource source, ObjectMapper json) {
        this.db = new JdbcTemplate(source); this.db.setQueryTimeout(5);
        this.json = json.copy().findAndRegisterModules().setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.LOWER_CAMEL_CASE)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_ENUMS_USING_INDEX)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
    }
    public Job enqueue(Unit unit, Grant grant, Instant deadline, int maxAttempts) {
        Objects.requireNonNull(unit); Objects.requireNonNull(grant); Objects.requireNonNull(deadline);
        if (!unit.asset().equals(grant.asset()) || maxAttempts < 1 || maxAttempts > 10)
            throw new IllegalArgumentException("Invalid work admission");
        Instant now = now();
        if (!deadline.isAfter(now) || !grant.expiresAt().isAfter(now) || deadline.isAfter(grant.expiresAt()))
            throw error("CONTENT_GRANT_EXPIRED");
        ContentAssetKey asset = unit.asset();
        String key = hash(List.of(asset.tenantId(), Long.toString(asset.videoId()), asset.sourceVersion(),
                asset.authorizationDomain(), Long.toString(asset.revocationEpoch()), unit.stage().name(),
                unit.segmentId(), unit.stageVersion()));
        UUID id = UUID.randomUUID();
        try {
            db.update("INSERT INTO agent_content_work(job_id,work_key,document,grant_json,status,max_attempts,deadline_at) VALUES(?,?,?,?,'QUEUED',?,?)",
                    id.toString(), key, encode(unit), encode(grant), maxAttempts, utc(deadline));
            return get(id);
        } catch (DuplicateKeyException race) {
            String existingId = db.queryForObject("SELECT job_id FROM agent_content_work WHERE work_key=?", String.class, key);
            Job existing = get(UUID.fromString(existingId));
            if (!existing.unit().equals(unit)) throw error("CONTENT_WORK_CONFLICT");
            return existing;
        }
    }
    public Job get(UUID id) {
        var rows = db.queryForList("SELECT * FROM agent_content_work WHERE job_id=?", id.toString());
        if (rows.size() != 1) throw error("CONTENT_WORK_NOT_FOUND");
        var r = rows.get(0);
        return new Job(id, decode(r.get("document"), Unit.class), decode(r.get("grant_json"), Grant.class),
                Status.valueOf(r.get("status").toString()), ((Number)r.get("attempts")).intValue(),
                ((Number)r.get("max_attempts")).intValue(), ((Number)r.get("fence")).longValue(),
                (String)r.get("lease_owner"), instant(r.get("lease_until")), instant(r.get("deadline_at")),
                r.get("result_ref") == null ? null : new Receipt(r.get("result_ref").toString(), r.get("result_digest").toString()),
                (String)r.get("error_code"));
    }
    public Optional<Lease> claim(String worker, Duration leaseDuration) {
        return claim(worker, leaseDuration, null, null);
    }
    public Optional<Lease> claim(String worker, Duration leaseDuration, Stage stage, String stageVersion) {
        if ((stage == null) != (stageVersion == null)) throw new IllegalArgumentException("Incomplete worker capability");
        if (stageVersion != null) identifier(stageVersion);
        identifier(worker); long seconds = seconds(leaseDuration, 600);
        db.update("UPDATE agent_content_work SET status='EXPIRED',fence=fence+1,updated_at=UTC_TIMESTAMP(6) WHERE status IN ('QUEUED','RETRY_WAIT','RUNNING') AND deadline_at<=UTC_TIMESTAMP(6) LIMIT 128");
        db.update("UPDATE agent_content_work SET status='FAILED',error_code='ATTEMPTS_EXHAUSTED',fence=fence+1,updated_at=UTC_TIMESTAMP(6) WHERE status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6) AND attempts>=max_attempts LIMIT 128");
        var candidates = db.queryForList("SELECT job_id,fence FROM agent_content_work WHERE deadline_at>UTC_TIMESTAMP(6) AND attempts<max_attempts AND ((status IN ('QUEUED','RETRY_WAIT') AND ready_at<=UTC_TIMESTAMP(6)) OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6))) AND (? IS NULL OR (JSON_UNQUOTE(JSON_EXTRACT(document,'$.stage'))=? AND JSON_UNQUOTE(JSON_EXTRACT(document,'$.stageVersion'))=?)) ORDER BY ready_at,job_id LIMIT 32", stage==null?null:stage.name(),stage==null?null:stage.name(),stageVersion);
        for (var row : candidates) {
            UUID id = UUID.fromString(row.get("job_id").toString()); long fence = ((Number)row.get("fence")).longValue();
            int changed = db.update("UPDATE agent_content_work SET status='RUNNING',fence=fence+1,attempts=attempts+1,lease_owner=?,lease_until=LEAST(deadline_at,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))),updated_at=UTC_TIMESTAMP(6) WHERE job_id=? AND fence=? AND deadline_at>UTC_TIMESTAMP(6) AND attempts<max_attempts AND ((status IN ('QUEUED','RETRY_WAIT') AND ready_at<=UTC_TIMESTAMP(6)) OR (status='RUNNING' AND lease_until<=UTC_TIMESTAMP(6)))",
                    worker, seconds, id.toString(), fence);
            if (changed == 1) return Optional.of(new Lease(id, fence + 1, worker));
        }
        return Optional.empty();
    }
    public Job requireLease(Lease lease) {
        var valid = db.queryForObject("SELECT COUNT(*) FROM agent_content_work WHERE job_id=? AND fence=? AND lease_owner=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) AND deadline_at>UTC_TIMESTAMP(6)",
                Integer.class, lease.jobId().toString(), lease.fence(), lease.worker());
        if (valid == null || valid != 1) throw error("CONTENT_LEASE_LOST");
        return get(lease.jobId());
    }
    public boolean heartbeat(Lease lease, Duration duration) {
        long seconds = seconds(duration, 600);
        return db.update("UPDATE agent_content_work SET lease_until=LEAST(deadline_at,TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6))),updated_at=UTC_TIMESTAMP(6) WHERE job_id=? AND fence=? AND lease_owner=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) AND deadline_at>UTC_TIMESTAMP(6)",
                seconds, lease.jobId().toString(), lease.fence(), lease.worker()) == 1;
    }
    public boolean complete(Lease lease, Receipt receipt) {
        int changed = db.update("UPDATE agent_content_work SET status='SUCCEEDED',result_ref=?,result_digest=?,updated_at=UTC_TIMESTAMP(6) WHERE job_id=? AND fence=? AND lease_owner=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) AND deadline_at>UTC_TIMESTAMP(6) AND (result_ref IS NULL OR (result_ref=? AND result_digest=?))",
                receipt.reference(), receipt.digest(), lease.jobId().toString(), lease.fence(), lease.worker(),receipt.reference(),receipt.digest());
        if (changed == 1) return true;
        Job job = get(lease.jobId());
        return job.status() == Status.SUCCEEDED && job.fence() == lease.fence()
                && Objects.equals(job.leaseOwner(), lease.worker()) && receipt.equals(job.result());
    }
    /** Durable private checkpoint, not a successful publication. Recovered leases may reuse it after authorization. */
    public boolean stageResult(Lease lease, Receipt receipt) {
        return db.update("UPDATE agent_content_work SET result_ref=?,result_digest=?,updated_at=UTC_TIMESTAMP(6) WHERE job_id=? AND fence=? AND lease_owner=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) AND deadline_at>UTC_TIMESTAMP(6) AND (result_ref IS NULL OR (result_ref=? AND result_digest=?))",
                receipt.reference(),receipt.digest(),lease.jobId().toString(),lease.fence(),lease.worker(),receipt.reference(),receipt.digest())==1;
    }
    public boolean fail(Lease lease, String errorCode, boolean retryable, Duration backoff) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid error code");
        long delay = seconds(backoff, 3600);
        return db.update("UPDATE agent_content_work SET status=IF(? AND attempts<max_attempts,'RETRY_WAIT','FAILED'),error_code=?,ready_at=TIMESTAMPADD(SECOND,?,UTC_TIMESTAMP(6)),updated_at=UTC_TIMESTAMP(6) WHERE job_id=? AND fence=? AND lease_owner=? AND status='RUNNING' AND lease_until>UTC_TIMESTAMP(6) AND deadline_at>UTC_TIMESTAMP(6)",
                retryable, errorCode, delay, lease.jobId().toString(), lease.fence(), lease.worker()) == 1;
    }
    public boolean revoke(Lease lease) {
        return db.update("UPDATE agent_content_work SET status='REVOKED',fence=fence+1,updated_at=UTC_TIMESTAMP(6) WHERE job_id=? AND fence=? AND lease_owner=? AND status='RUNNING'",
                lease.jobId().toString(), lease.fence(), lease.worker()) == 1;
    }
    /** Explicit retry with renewed authority. Lifetime attempt count is never reset. */
    public boolean requeue(UUID jobId, Grant grant, Instant deadline, int additionalAttempts) {
        Job prior = get(jobId);
        if (!prior.unit().asset().equals(grant.asset()) || additionalAttempts < 1 || additionalAttempts > 10)
            throw new IllegalArgumentException("Invalid content retry");
        if (prior.status() != Status.FAILED && prior.status() != Status.EXPIRED) return false;
        if (prior.attempts() >= 10) throw error("CONTENT_RETRY_BUDGET_EXHAUSTED");
        if (!deadline.isAfter(now()) || deadline.isAfter(grant.expiresAt())) throw error("CONTENT_GRANT_EXPIRED");
        int maximum = Math.min(10, Math.max(prior.maxAttempts(), prior.attempts() + additionalAttempts));
        return db.update("UPDATE agent_content_work SET status='QUEUED',grant_json=?,max_attempts=?,deadline_at=?,ready_at=UTC_TIMESTAMP(6),fence=fence+1,lease_owner=NULL,lease_until=NULL,error_code=NULL,updated_at=UTC_TIMESTAMP(6) WHERE job_id=? AND fence=? AND status IN ('FAILED','EXPIRED')",
                encode(grant), maximum, utc(deadline), jobId.toString(), prior.fence()) == 1;
    }
    /** Internal subscription metadata; caller still performs live user read authorization. */
    public void subscribe(UUID jobId, String tenant, String owner, UUID request, Instant deadline) {
        ContentAssetKey asset = get(jobId).unit().asset();
        if (!asset.tenantId().equals(tenant) || owner == null || owner.isBlank() || owner.length()>64
                || deadline == null || !deadline.isAfter(now())) throw error("INVALID_CONTENT_SUBSCRIPTION");
        try {
            db.update("INSERT INTO agent_content_subscription(tenant_id,owner_user_id,request_id,job_id,status,deadline_at) VALUES(?,?,?,?,'ACTIVE',?)",
                    tenant, owner, request.toString(), jobId.toString(), utc(deadline));
        } catch (DuplicateKeyException duplicate) {
            String previous = db.queryForObject("SELECT job_id FROM agent_content_subscription WHERE tenant_id=? AND owner_user_id=? AND request_id=?", String.class, tenant, owner, request.toString());
            if (!jobId.toString().equals(previous)) throw error("CONTENT_SUBSCRIPTION_CONFLICT");
        }
    }
    public boolean cancelSubscription(String tenant, String owner, UUID request) {
        return db.update("UPDATE agent_content_subscription SET status='CANCELLED' WHERE tenant_id=? AND owner_user_id=? AND request_id=? AND status='ACTIVE'",
                tenant, owner, request.toString()) == 1;
    }
    public int activeSubscribers(UUID jobId) {
        return Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) FROM agent_content_subscription WHERE job_id=? AND status='ACTIVE' AND deadline_at>UTC_TIMESTAMP(6)", Integer.class, jobId.toString()));
    }
    private Instant now() {
        return Objects.requireNonNull(db.queryForObject("SELECT UTC_TIMESTAMP(6)",
                (row, ordinal) -> row.getObject(1, LocalDateTime.class))).toInstant(ZoneOffset.UTC);
    }
    private static Instant instant(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
        if (value instanceof Timestamp stamp) return stamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
        throw new IllegalStateException("Unsupported content timestamp representation");
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static long seconds(Duration value, long max) {
        if (value == null || value.isNegative() || value.isZero() || value.getNano() != 0 || value.getSeconds()>max)
            throw new IllegalArgumentException("Invalid work duration");
        return value.getSeconds();
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Content serialization failed", e); }
    }
    private <T> T decode(Object value, Class<T> type) {
        try { return json.readValue(value.toString(), type); } catch (Exception e) { throw new IllegalStateException("Invalid stored content work", e); }
    }
    private static String hash(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array()); digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static AgentException error(String code) { return new AgentException(code, code); }
}
