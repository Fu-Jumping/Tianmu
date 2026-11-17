package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.ContentReadAuthority;
import java.time.*;
import java.util.*;

/** Explicit full-video admission. Deliberately not wired until processing grants and workers exist. */
public final class NotesAdmissionService {
    private final ContentReadAuthority authority;
    private final MysqlNotesJobStore store;
    private final NotesJob.Policy policy;
    private final long maxDurationMs;
    private final Duration lifetime;
    private final Clock clock;
    public NotesAdmissionService(ContentReadAuthority authority, MysqlNotesJobStore store, NotesJob.Policy policy,
                                 long maxDurationMs, Duration lifetime, Clock clock) {
        this.authority=Objects.requireNonNull(authority); this.store=Objects.requireNonNull(store);
        this.policy=Objects.requireNonNull(policy); this.clock=Objects.requireNonNull(clock);
        if (maxDurationMs < 1 || maxDurationMs > 86400000 || lifetime == null || lifetime.isNegative()
                || lifetime.isZero() || lifetime.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Invalid notes admission limits");
        this.maxDurationMs=maxDurationMs; this.lifetime=lifetime;
    }
    public NotesJob.Job create(long videoId, String sourceVersion, String idempotencyKey,
                               AccessScope viewer, CallControl control) {
        requireViewer(videoId,viewer,control);
        NotesJob.text(sourceVersion); NotesJob.text(idempotencyKey);
        var current=authority.current(videoId,viewer,control);
        requireIdentity(videoId,viewer,current);
        if (!current.asset().sourceVersion().equals(sourceVersion)) throw error("VIDEO_VERSION_CHANGED");
        if (current.durationMs() > maxDurationMs) throw error("NOTES_DURATION_LIMIT");
        control.checkpoint();
        return store.create(new NotesJob.Create(viewer.tenantId(),viewer.userId(),current.asset(),current.durationMs(),
                policy,idempotencyKey,clock.instant().plus(lifetime)));
    }
    public NotesJob.Job current(UUID jobId, AccessScope viewer, CallControl control) {
        Objects.requireNonNull(viewer); Objects.requireNonNull(control); control.checkpoint();
        var job=store.get(jobId,viewer.tenantId(),viewer.userId());
        long videoId=job.request().asset().videoId();
        requireViewer(videoId,viewer,control);
        var current=authority.current(videoId,viewer,control); requireIdentity(videoId,viewer,current);
        if (!current.asset().equals(job.request().asset()) || current.durationMs()!=job.request().durationMs())
            throw error("VIDEO_VERSION_CHANGED");
        control.checkpoint(); return job;
    }
    public record Lookup(ContentReadAuthority.Identity identity,Optional<NotesJob.Job> job) {}
    public Lookup lookup(long videoId,AccessScope viewer,CallControl control) {
        requireViewer(videoId,viewer,control);
        var current=authority.current(videoId,viewer,control);requireIdentity(videoId,viewer,current);
        var key=new NotesJob.Create(viewer.tenantId(),viewer.userId(),current.asset(),current.durationMs(),policy,"lookup",clock.instant().plus(lifetime));
        control.checkpoint();return new Lookup(current,store.find(key));
    }
    private static void requireViewer(long videoId,AccessScope viewer,CallControl control) {
        Objects.requireNonNull(viewer); Objects.requireNonNull(control); control.checkpoint();
        if (videoId <= 0 || viewer.tenantId()==null || viewer.userId()==null
                || viewer.tenantId().isBlank() || viewer.userId().isBlank() || !viewer.readableVideoIds().contains(videoId))
            throw error("FORBIDDEN");
    }
    private static void requireIdentity(long videoId,AccessScope viewer,ContentReadAuthority.Identity current) {
        if (current==null || current.asset().videoId()!=videoId || !current.asset().tenantId().equals(viewer.tenantId()))
            throw error("FORBIDDEN");
    }
    private static AgentException error(String code) { return new AgentException(code,code); }
}
