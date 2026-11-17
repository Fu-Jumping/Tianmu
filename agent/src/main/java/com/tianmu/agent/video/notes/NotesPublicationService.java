package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.*;
import java.util.*;

/** Independent artifact publication/consumption gates; no fake chat Run or feedback report permission. */
public final class NotesPublicationService {
    private final MysqlNotesJobStore notes;
    private final NotesDocumentObjects objects;
    private final NotesCoordinator.ProcessingGrants processing;
    private final NotesAdmissionService readers;
    private final String qualityPolicyDigest;
    public NotesPublicationService(MysqlNotesJobStore notes,NotesDocumentObjects objects,NotesCoordinator.ProcessingGrants processing,
                                   NotesAdmissionService readers,String qualityPolicyDigest) {
        this.notes=Objects.requireNonNull(notes);this.objects=Objects.requireNonNull(objects);this.processing=Objects.requireNonNull(processing);this.readers=Objects.requireNonNull(readers);
        NotesJob.digest(qualityPolicyDigest);this.qualityPolicyDigest=qualityPolicyDigest;
    }
    public boolean publish(UUID id,String tenant,String user,NotesMaterialReader.Prepared material,
                           NotesDocumentObjects.Reference reference,NotesDocument.QualityReceipt quality,UUID summaryWorkId,CallControl control) {
        control.checkpoint();var root=notes.get(id,tenant,user);
        if(root.status()!=NotesJob.Status.RUNNING || !root.request().deadline().isAfter(Instant.now()) || !root.id().equals(material.rootId())
                || root.generation()!=material.generation() || root.version()!=material.version() || !Objects.equals(root.planDigest(),material.planDigest())) throw error("NOTES_SOURCE_CONFLICT");
        processing.requireRoot(root,control);
        var tokens=notes.sourceSnapshot(id,tenant,user);
        if(tokens.size()!=material.sources().size())throw error("NOTES_SOURCE_CONFLICT");
        Map<String,NotesMaterialReader.Source> sourceMap=new HashMap<>();
        for(var source:material.sources())if(sourceMap.put(source.plan().unitKey(),source)!=null)throw error("NOTES_SOURCE_CONFLICT");
        for(var token:tokens) {
            var source=sourceMap.get(token.unitKey());
            if(source==null || !source.receipt().reference().equals(token.receiptRef()) || !source.receipt().digest().equals(token.receiptDigest())) throw error("NOTES_SOURCE_CONFLICT");
        }
        var document=objects.read(root,reference,qualityPolicyDigest,control);
        material.requireMatching(document);document.requirePublishable(quality,qualityPolicyDigest);
        processing.requireRoot(root,control);control.checkpoint();
        return notes.publish(id,tenant,user,root.version(),root.generation(),root.planDigest(),reference,tokens,qualityPolicyDigest,quality,summaryWorkId);
    }
    public NotesDocument read(UUID id,AccessScope viewer,CallControl control) {
        var before=readers.current(id,viewer,control);
        if(before.status()!=NotesJob.Status.READY)throw error("NOTES_NOT_READY");
        var ref=notes.publishedReference(id,viewer.tenantId(),viewer.userId()).orElseThrow(()->error("NOTES_NOT_READY"));
        if(!Objects.equals(before.documentDigest(),ref.documentDigest()))throw error("NOTES_SOURCE_CONFLICT");
        // Published historical documents retain their own frozen quality policy; upgrading the
        // active generator must not reinterpret that policy. Object integrity and authorization remain live.
        var document=objects.read(before,ref,control);
        var after=readers.current(id,viewer,control);
        if(after.status()!=NotesJob.Status.READY || after.version()!=before.version() || after.generation()!=before.generation()
                || !Objects.equals(after.documentDigest(),before.documentDigest()))throw error("NOTES_SOURCE_CONFLICT");
        return document;
    }
    public String copy(UUID id,AccessScope viewer,CallControl control){return read(id,viewer,control).plainText();}
    private static AgentException error(String code){return new AgentException(code,code);}
}
