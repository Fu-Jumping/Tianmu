package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import java.time.*;
import java.util.*;

/** Viewer-requested continuation, retaining completed evidence and accounting for an uncertain call. */
public final class NotesRetryService {
    private final NotesAdmissionService readers;private final MysqlNotesJobStore notes;private final MysqlContentWorkStore content;
    private final ContentWorkService work;private final NotesCoordinator.ProcessingGrants grants;
    public NotesRetryService(NotesAdmissionService readers,MysqlNotesJobStore notes,MysqlContentWorkStore content,
                             ContentWorkService work,NotesCoordinator.ProcessingGrants grants){
        this.readers=readers;this.notes=notes;this.content=content;this.work=work;this.grants=grants;
    }
    public NotesJob.Job retry(UUID id,AccessScope viewer,CallControl control){
        var root=readers.current(id,viewer,control);
        if(root.status()!=NotesJob.Status.PARTIAL)throw error();
        String reason=notes.errorCode(id,viewer.tenantId(),viewer.userId());
        if(!Set.of("NOTES_CALL_UNKNOWN","NOTES_QUALITY_REJECTED").contains(reason))throw error();
        var dependencies=new ArrayList<>(notes.dependencies(id,viewer.tenantId(),viewer.userId()));
        notes.synthesisDependency(id,viewer.tenantId(),viewer.userId()).ifPresent(dependencies::add);
        var failed=dependencies.stream().filter(d->d.workId()!=null && content.get(d.workId()).status()==ContentWork.Status.FAILED).toList();
        if(failed.size()!=1)throw error();
        var dependency=failed.get(0);var task=content.get(dependency.workId());
        if(task.attempts()>=("NOTES_QUALITY_REJECTED".equals(reason)?4:3))throw error();
        grants.requireRoot(root,control);
        var grant=grants.issue(root,dependency.unit(),UUID.randomUUID(),control);
        if(grant==null || !grant.asset().equals(root.request().asset()))throw error();
        var deadline=Collections.min(List.of(root.request().deadline(),grant.expiresAt(),Instant.now().plusSeconds(600)));
        work.requireAdmission(task.unit(),grant,control);grants.requireRoot(root,control);
        boolean resumed="NOTES_CALL_UNKNOWN".equals(reason)
                ?notes.retryUnknown(id,viewer.tenantId(),viewer.userId(),root.version(),task.id(),grant,deadline)
                :notes.retryQuality(id,viewer.tenantId(),viewer.userId(),root.version(),task.id(),grant,deadline,100000L);
        if(!resumed)throw error();
        return readers.current(id,viewer,control);
    }
    private static AgentException error(){return new AgentException("NOTES_RETRY_UNAVAILABLE","Notes retry unavailable");}
}
