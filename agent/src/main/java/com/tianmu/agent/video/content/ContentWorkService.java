package com.tianmu.agent.video.content;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.video.content.ContentWork.*;

/** Authorization gates for platform content production. Does not authorize end-user consumption. */
public final class ContentWorkService {
    private final MysqlContentWorkStore store;
    private final ContentProcessingAuthority authority;
    private final Duration retryBackoff;
    public ContentWorkService(MysqlContentWorkStore store, ContentProcessingAuthority authority, Duration retryBackoff) {
        this.store = Objects.requireNonNull(store); this.authority = Objects.requireNonNull(authority);
        if (retryBackoff == null || retryBackoff.isNegative() || retryBackoff.isZero()
                || retryBackoff.getNano() != 0 || retryBackoff.getSeconds() > 3600)
            throw new IllegalArgumentException("Invalid authority retry delay");
        this.retryBackoff = retryBackoff;
    }
    public Job admit(Unit unit, Grant grant, Instant deadline, int maxAttempts, CallControl control) {
        requireAdmission(unit,grant,control);
        return store.enqueue(unit, grant, deadline, maxAttempts);
    }
    /** Separate the external authority call from a short caller-owned enqueue/binding transaction. */
    public void requireAdmission(Unit unit, Grant grant, CallControl control) {
        if (!unit.asset().equals(grant.asset())) throw new IllegalArgumentException("Grant belongs to another media version");
        require(grant, Checkpoint.ADMISSION, control);
    }
    public Optional<Lease> claim(String worker, Duration leaseDuration, CallControl control) {
        return claim(worker, leaseDuration, null, null, control);
    }
    public Optional<Lease> claim(String worker, Duration leaseDuration, Stage stage, String stageVersion, CallControl control) {
        control.checkpoint();
        Optional<Lease> claimed = store.claim(worker, leaseDuration, stage, stageVersion);
        if (claimed.isEmpty()) return claimed;
        Lease lease = claimed.get();
        try {
            require(store.requireLease(lease).grant(), Checkpoint.CLAIM, control);
            return claimed;
        } catch (AgentException failure) {
            reject(lease, failure); throw failure;
        }
    }
    /** Invoke immediately before the model call; a previously claimed lease is not a grant. */
    public Job requireDispatch(Lease lease, CallControl control) {
        Job job = store.requireLease(lease);
        try { require(job.grant(), Checkpoint.DISPATCH, control); }
        catch (AgentException failure) { reject(lease, failure); throw failure; }
        store.requireLease(lease);
        return job;
    }
    public boolean complete(Lease lease, Receipt receipt, CallControl control) {
        Job job = store.get(lease.jobId());
        // Reconcile an unknown acknowledgement without repeating the model or result write.
        if (job.status() == Status.SUCCEEDED && job.fence() == lease.fence()
                && Objects.equals(job.leaseOwner(), lease.worker()) && receipt.equals(job.result())) {
            require(job.grant(), Checkpoint.COMMIT, control);
            return true;
        }
        job = store.requireLease(lease);
        try { require(job.grant(), Checkpoint.COMMIT, control); }
        catch (AgentException failure) { reject(lease, failure); throw failure; }
        return store.complete(lease, receipt);
    }
    public boolean retry(UUID jobId, Grant renewed, Instant deadline, int additionalAttempts, CallControl control) {
        if (!store.get(jobId).unit().asset().equals(renewed.asset()))
            throw new IllegalArgumentException("Retry cannot change the media authority");
        require(renewed, Checkpoint.ADMISSION, control);
        return store.requeue(jobId, renewed, deadline, additionalAttempts);
    }
    public boolean renewUnclaimed(UUID jobId,UUID expectedGrant,long expectedFence,Grant renewed,Instant deadline,CallControl control) {
        Job current=store.get(jobId);
        if(!current.unit().asset().equals(renewed.asset()))throw new IllegalArgumentException("Renewal cannot change media authority");
        require(renewed,Checkpoint.ADMISSION,control);
        return store.renewUnclaimed(jobId,expectedGrant,expectedFence,renewed,deadline);
    }
    private void require(Grant grant, Checkpoint checkpoint, CallControl control) {
        control.checkpoint();
        if (!grant.expiresAt().isAfter(Instant.now())) throw new AgentException("CONTENT_GRANT_EXPIRED", "Content processing grant expired");
        authority.require(grant, checkpoint, control);
        control.checkpoint();
    }
    private void reject(Lease lease, AgentException failure) {
        if (Set.of("FORBIDDEN", "CONTENT_GRANT_EXPIRED", "CONTENT_GRANT_REVOKED", "VIDEO_VERSION_CHANGED").contains(failure.code()))
            store.revoke(lease);
        else if(Set.of("CONTENT_MEDIA_UNSUPPORTED","INVALID_CONTENT_REQUEST").contains(failure.code()))
            store.fail(lease,failure.code(),false,retryBackoff);
        else store.fail(lease, "CONTENT_AUTHORITY_UNAVAILABLE", true, retryBackoff);
    }
}
