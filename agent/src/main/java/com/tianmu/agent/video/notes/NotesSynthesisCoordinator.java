package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Follow-on advancement after NotesCoordinator reports MATERIALS_READY; no additional queue or scheduler. */
public final class NotesSynthesisCoordinator {
    public enum Outcome { ENQUEUED, WAITING, PUBLISHED, PARTIAL, STOPPED, RACE }
    private final MysqlNotesJobStore notes;
    private final MysqlContentWorkStore content;
    private final ContentWorkService authority;
    private final NotesCoordinator.ProcessingGrants processing;
    private final NotesSynthesisWorker worker;
    private final Duration attemptLimit;
    public NotesSynthesisCoordinator(MysqlNotesJobStore notes,MysqlContentWorkStore content,ContentWorkService authority,
            NotesCoordinator.ProcessingGrants processing,NotesSynthesisWorker worker,Duration attemptLimit) {
        this.notes=Objects.requireNonNull(notes);this.content=Objects.requireNonNull(content);this.authority=Objects.requireNonNull(authority);
        this.processing=Objects.requireNonNull(processing);this.worker=Objects.requireNonNull(worker);
        if(attemptLimit==null || attemptLimit.getNano()!=0 || attemptLimit.getSeconds()<1 || attemptLimit.getSeconds()>3600)throw new IllegalArgumentException("Invalid synthesis deadline");
        this.attemptLimit=attemptLimit;
    }
    public Outcome advance(UUID id,String tenant,String user,CallControl control) {
        NotesLeaseRunner.check(control);var root=notes.get(id,tenant,user);
        if(root.status()==NotesJob.Status.READY)return Outcome.PUBLISHED;
        if(root.status()!=NotesJob.Status.RUNNING)return Outcome.STOPPED;
        processing.requireRoot(root,control);
        if(notes.hasUnknownCalls(id,tenant,user))return partial(root,"NOTES_CALL_UNKNOWN");
        var current=notes.synthesisDependency(id,tenant,user);
        if(current.isPresent() && current.get().workId()!=null){
            var task=content.get(current.get().workId());
            if(task.status()==ContentWork.Status.SUCCEEDED)return worker.publishCompleted(root,control)?Outcome.PUBLISHED:Outcome.RACE;
            long renewAhead=Math.max(1,Math.min(30,attemptLimit.getSeconds()/4));
            if(task.attempts()==0 && (task.status()==ContentWork.Status.EXPIRED || task.status()==ContentWork.Status.QUEUED
                    && !task.deadline().isAfter(Instant.now().plusSeconds(renewAhead)))) {
                var unit=current.get().unit();
                var renewId=UUID.nameUUIDFromBytes((id+":"+unit.segmentId(id)+":renew:"+task.grant().id()).getBytes(StandardCharsets.UTF_8));
                var renewed=processing.issue(root,unit,renewId,control);
                if(renewed==null || !renewed.asset().equals(root.request().asset()))throw new AgentException("VIDEO_VERSION_CHANGED","Renewed synthesis source changed");
                var deadline=Collections.min(List.of(root.request().deadline(),renewed.expiresAt(),Instant.now().plus(attemptLimit)));
                return authority.renewUnclaimed(task.id(),task.grant().id(),task.fence(),renewed,deadline,control)?Outcome.WAITING:Outcome.RACE;
            }
            if(Set.of(ContentWork.Status.QUEUED,ContentWork.Status.RUNNING,ContentWork.Status.RETRY_WAIT).contains(task.status()))return Outcome.WAITING;
            return partial(root,"NOTES_SYNTHESIS_INCOMPLETE");
        }
        var tokens=notes.sourceSnapshot(id,tenant,user);String digest=MysqlNotesJobStore.sourceSnapshotDigest(tokens);
        var dependency=notes.ensureSynthesisUnit(id,tenant,user,root.version(),digest);
        if(dependency.isEmpty())return Outcome.RACE;
        var unit=dependency.get().unit();
        var grant=processing.issue(root,unit,UUID.nameUUIDFromBytes((id+":"+unit.segmentId(id)).getBytes(StandardCharsets.UTF_8)),control);
        if(grant==null || !grant.asset().equals(root.request().asset()))throw new AgentException("VIDEO_VERSION_CHANGED","Synthesis source changed");
        Instant deadline=Collections.min(List.of(root.request().deadline(),grant.expiresAt(),Instant.now().plus(attemptLimit)));
        var work=new ContentWork.Unit(root.request().asset(),ContentWork.Stage.SUMMARY,unit.segmentId(id),root.request().policy().generationVersion(),0,root.request().durationMs());
        authority.requireAdmission(work,grant,control);
        return notes.enqueueSynthesisBound(id,tenant,user,root.version(),root.generation(),grant,deadline,2,tokens).isPresent()?Outcome.ENQUEUED:Outcome.RACE;
    }
    private Outcome partial(NotesJob.Job root,String reason){return notes.transition(root.id(),root.request().tenantId(),root.request().userId(),root.version(),NotesJob.Status.PARTIAL,reason)?Outcome.PARTIAL:Outcome.RACE;}
}
