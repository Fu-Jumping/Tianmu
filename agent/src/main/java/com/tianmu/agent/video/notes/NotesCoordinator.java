package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** One bounded, replayable advancement. Hosting/scheduling remains outside this business class. */
public final class NotesCoordinator {
    /** Must revalidate the persisted requesting subject, purpose, media and revocation epoch.
     * A platform-only public-media grant without that check is not sufficient. */
    public interface ProcessingGrants {
        void requireRoot(NotesJob.Job root,CallControl control);
        ContentWork.Grant issue(NotesJob.Job root,NotesJob.PlanUnit unit,UUID requestId,CallControl control);
    }
    public enum Outcome { PLANNED, ENQUEUED, WAITING, MATERIALS_READY, PARTIAL, STOPPED, RACE }
    private final MysqlNotesJobStore notes;
    private final MysqlContentWorkStore content;
    private final ContentWorkService work;
    private final ProcessingGrants grants;
    private final Clock clock;
    private final Duration attemptLimit;
    public NotesCoordinator(MysqlNotesJobStore notes,MysqlContentWorkStore content,ContentWorkService work,
                            ProcessingGrants grants,Clock clock,Duration attemptLimit) {
        this.notes=Objects.requireNonNull(notes); this.content=Objects.requireNonNull(content); this.work=Objects.requireNonNull(work);
        this.grants=Objects.requireNonNull(grants); this.clock=Objects.requireNonNull(clock);
        if (attemptLimit==null || attemptLimit.getNano()!=0 || attemptLimit.getSeconds()<1 || attemptLimit.getSeconds()>3600)
            throw new IllegalArgumentException("Invalid attempt deadline");
        this.attemptLimit=attemptLimit;
    }
    public Outcome advance(UUID rootId,String tenant,String user,CallControl control) {
        control.checkpoint(); var root=notes.get(rootId,tenant,user);
        if(root.status()!=NotesJob.Status.QUEUED && root.status()!=NotesJob.Status.RUNNING) return Outcome.STOPPED;
        if(!root.request().deadline().isAfter(clock.instant()))
            return stop(root,NotesJob.Status.EXPIRED,"NOTES_EXPIRED");
        grants.requireRoot(root,control);
        if(root.status()==NotesJob.Status.QUEUED) {
            var asset=root.request().asset();
            String sourceIdentity=NotesJob.hash(asset.tenantId(),Long.toString(asset.videoId()),asset.sourceVersion(),asset.authorizationDomain(),
                    Long.toString(asset.revocationEpoch()),Long.toString(root.request().durationMs()));
            return notes.installPlan(rootId,tenant,user,root.version(),NotesJob.sourcePlan(rootId,root.request().durationMs(),root.request().policy(),sourceIdentity))
                    ? Outcome.PLANNED:Outcome.RACE;
        }
        if(notes.hasUnknownCalls(rootId,tenant,user)) return stop(root,NotesJob.Status.PARTIAL,"NOTES_CALL_UNKNOWN");
        var dependencies=notes.dependencies(rootId,tenant,user);
        var expected=notes.plan(rootId,tenant,user);
        if(expected.isEmpty() || dependencies.size()!=expected.size()) throw new AgentException("NOTES_INVALID_PLAN","Persistent plan missing");
        // Validate every bound work before deciding to send another unit. A failed tail cannot be hidden
        // by an unbound earlier unit; no additional paid work while an existing unit is active.
        MysqlNotesJobStore.Dependency missing=null;
        for(var dependency:dependencies) {
            if(dependency.workId()==null) {if(missing==null)missing=dependency;continue;}
            var job=content.get(dependency.workId()); requireBound(root,dependency.unit(),job);
            long renewAhead=Math.max(1,Math.min(30,attemptLimit.getSeconds()/4));
            if(job.attempts()==0 && (job.status()==ContentWork.Status.EXPIRED || job.status()==ContentWork.Status.QUEUED
                    && !job.deadline().isAfter(clock.instant().plusSeconds(renewAhead)))) {
                var requestId=UUID.nameUUIDFromBytes((root.id()+":"+dependency.unit().segmentId(root.id())+":renew:"+job.grant().id()).getBytes(StandardCharsets.UTF_8));
                var renewed=grants.issue(root,dependency.unit(),requestId,control);
                if(renewed==null || !renewed.asset().equals(root.request().asset()))throw new AgentException("VIDEO_VERSION_CHANGED","Renewed authority changed");
                var deadline=Collections.min(List.of(root.request().deadline(),renewed.expiresAt(),clock.instant().plus(attemptLimit)));
                return work.renewUnclaimed(job.id(),job.grant().id(),job.fence(),renewed,deadline,control)?Outcome.WAITING:Outcome.RACE;
            }
            if(job.status()==ContentWork.Status.QUEUED || job.status()==ContentWork.Status.RUNNING || job.status()==ContentWork.Status.RETRY_WAIT)
                return Outcome.WAITING;
            if(job.status()!=ContentWork.Status.SUCCEEDED || job.result()==null)
                return stop(root,NotesJob.Status.PARTIAL,"NOTES_SOURCE_INCOMPLETE");
        }
        if(missing==null) return Outcome.MATERIALS_READY;
        var unit=missing.unit();
        UUID requestId=UUID.nameUUIDFromBytes((root.id()+":"+unit.segmentId(root.id())).getBytes(StandardCharsets.UTF_8));
        try {
            var grant=grants.issue(root,unit,requestId,control);
            if(grant==null || !grant.asset().equals(root.request().asset())) throw new AgentException("VIDEO_VERSION_CHANGED","Processing authority changed");
            Instant deadline=Collections.min(List.of(root.request().deadline(),grant.expiresAt(),clock.instant().plus(attemptLimit)));
            if(!deadline.isAfter(clock.instant())) return Outcome.WAITING;
            var task=new ContentWork.Unit(root.request().asset(),unit.stage(),unit.segmentId(root.id()),root.request().policy().analysisVersion(),unit.dependencyStartMs(),unit.dependencyEndMs());
            // Network authorization is outside the short transaction. Enqueue and binding commit
            // together, so another worker never observes an unbound, claimable notes unit.
            work.requireAdmission(task,grant,control);
            var admitted=notes.enqueueBound(root.id(),tenant,user,root.version(),root.generation(),unit.unitKey(),grant,deadline,2);
            if(admitted.isEmpty()) return Outcome.RACE;
            requireBound(root,unit,admitted.get()); return Outcome.ENQUEUED;
        } catch(AgentException failure) {
            if(Set.of("FORBIDDEN","CONTENT_GRANT_REVOKED").contains(failure.code())) return stop(root,NotesJob.Status.REVOKED,failure.code());
            if("VIDEO_VERSION_CHANGED".equals(failure.code())) return stop(root,NotesJob.Status.STALE,failure.code());
            throw failure; // transient authority unavailability never creates a permissive grant
        }
    }
    private Outcome stop(NotesJob.Job root,NotesJob.Status status,String error) {
        boolean changed=notes.transition(root.id(),root.request().tenantId(),root.request().userId(),root.version(),status,error);
        if(!changed)return Outcome.RACE;
        return status==NotesJob.Status.PARTIAL?Outcome.PARTIAL:Outcome.STOPPED;
    }
    private static void requireBound(NotesJob.Job root,NotesJob.PlanUnit unit,ContentWork.Job work) {
        var actual=work.unit();
        if(!actual.asset().equals(root.request().asset()) || actual.stage()!=unit.stage() || !actual.segmentId().equals(unit.segmentId(root.id()))
                || !actual.stageVersion().equals(root.request().policy().analysisVersion())
                || actual.startMs()!=unit.dependencyStartMs() || actual.endMs()!=unit.dependencyEndMs())
            throw new AgentException("NOTES_WORK_CONFLICT","Bound work identity mismatch");
    }
}
