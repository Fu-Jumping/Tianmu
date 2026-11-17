package com.tianmu.agent.video.notes;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatic, separately accounted draft/check phases on the existing SUMMARY lease. No auto assembly. */
public final class NotesSynthesisWorker implements AutoCloseable {
    private final MysqlNotesJobStore notes;
    private final MysqlContentWorkStore content;
    private final ContentWorkService work;
    private final NotesMaterialReader materials;
    private final NotesSynthesisService synthesis;
    private final NotesSynthesisObjects checkpoints;
    private final NotesDocumentObjects documents;
    private final NotesPublicationService publication;
    private final NotesCoordinator.ProcessingGrants processing;
    private final NotesLeaseRunner runner;
    private final long reservation;
    public NotesSynthesisWorker(MysqlNotesJobStore notes,MysqlContentWorkStore content,ContentWorkService work,
            NotesMaterialReader materials,NotesSynthesisService synthesis,NotesSynthesisObjects checkpoints,
            NotesDocumentObjects documents,NotesPublicationService publication,NotesCoordinator.ProcessingGrants processing,
            String worker,Duration lease,Duration heartbeat,long reservation) {
        this.notes=Objects.requireNonNull(notes);this.content=Objects.requireNonNull(content);this.work=Objects.requireNonNull(work);
        this.materials=Objects.requireNonNull(materials);this.synthesis=Objects.requireNonNull(synthesis);this.checkpoints=Objects.requireNonNull(checkpoints);
        this.documents=Objects.requireNonNull(documents);this.publication=Objects.requireNonNull(publication);this.processing=Objects.requireNonNull(processing);
        if(reservation<1)throw new IllegalArgumentException("Invalid synthesis reservation");this.reservation=reservation;
        runner=new NotesLeaseRunner(notes,content,work,worker,synthesis.generationVersion(),lease,heartbeat);
    }
    public NotesLeaseRunner.Outcome runOnce(CallControl parent) {
        return runner.runOnce(ContentWork.Stage.SUMMARY,parent,(job,bound,lease,control)->{
            var root=bound.job();requireRevision(root);processing.requireRoot(root,control);
            var material=materials.read(root.id(),root.request().tenantId(),root.request().userId(),control);
            String sourceDigest=sourceDigest(root,bound.unit(),control);
            var draft=phase(root,bound.unit(),lease,material,sourceDigest,NotesSynthesisObjects.Phase.DRAFT,null,null,control,parent);
            var checked=phase(root,bound.unit(),lease,material,sourceDigest,NotesSynthesisObjects.Phase.CHECK,draft.document(),null,control,parent);
            if(!checked.quality().passed() || !checked.quality().majorIssues().isEmpty()) {
                var repaired=phase(root,bound.unit(),lease,material,sourceDigest,NotesSynthesisObjects.Phase.REPAIR,draft.document(),checked.quality(),control,parent);
                checked=phase(root,bound.unit(),lease,material,sourceDigest,NotesSynthesisObjects.Phase.RECHECK,repaired.document(),null,control,parent);
                draft=repaired;
                if(!checked.quality().passed() || !checked.quality().majorIssues().isEmpty())
                    checked=phase(root,bound.unit(),lease,material,sourceDigest,NotesSynthesisObjects.Phase.ARBITRATE,repaired.document(),checked.quality(),control,parent);
            }
            material.requireMatching(checked.document());
            if(!checked.document().contentDigest().equals(draft.document().contentDigest()))throw error("NOTES_SOURCE_CONFLICT");
            if(!checked.quality().passed() || !checked.quality().majorIssues().isEmpty()) {
                notes.transition(root.id(),root.request().tenantId(),root.request().userId(),root.version(),NotesJob.Status.PARTIAL,"NOTES_QUALITY_REJECTED");
                throw error("NOTES_QUALITY_REJECTED");
            }
            var reference=documents.stage(root,material,checked.document(),checked.quality(),synthesis.qualityPolicyDigest(),control);
            var receipt=new ContentWork.Receipt(reference.objectKey(),reference.sha256());
            processing.requireRoot(root,control);work.requireDispatch(lease,control);
            if(!content.stageResult(lease,receipt) || !work.complete(lease,receipt,control))return false;
            return publication.publish(root.id(),root.request().tenantId(),root.request().userId(),material,reference,checked.quality(),job.id(),control);
        });
    }
    private NotesSynthesisObjects.Checkpoint phase(NotesJob.Job root,NotesJob.PlanUnit unit,ContentWork.Lease lease,
            NotesMaterialReader.Prepared material,String sourceDigest,NotesSynthesisObjects.Phase phase,NotesDocument draft,NotesDocument.QualityReceipt priorQuality,
            CallControl control,CallControl parent) {
        String operation=unit.segmentId(root.id())+":"+phase.name().toLowerCase(Locale.ROOT);
        var call=notes.prepareCall(root.id(),root.request().tenantId(),root.request().userId(),root.generation(),operation,reservation);
        if(call.state()==NotesJob.CallState.RESULT_STAGED || call.state()==NotesJob.CallState.COMMITTED) {
            var value=load(root,material,sourceDigest,phase,call,control);
            work.requireDispatch(lease,control);
            if(!notes.commitCall(call.id(),call.reservedMicros()))throw error("NOTES_CHECKPOINT_CONFLICT");
            return value;
        }
        if(call.state()!=NotesJob.CallState.PREPARED)throw error("NOTES_CALL_UNKNOWN");
        AtomicBoolean sent=new AtomicBoolean();
        var modelControl=control.withDispatchBarrier(()->{
            NotesLeaseRunner.check(control);parent.beforeDispatch();processing.requireRoot(root,control);work.requireDispatch(lease,control);
            if(!sent.get()){if(!notes.beginWorkDispatch(call.id(),lease))throw error("NOTES_DISPATCH_CONFLICT");sent.set(true);}
        });
        try {
            modelControl.beforeDispatch();
            NotesDocument document=switch(phase){
                case DRAFT -> synthesis.draft(material,"视频内容笔记",modelControl);
                case REPAIR -> synthesis.repair(material,Objects.requireNonNull(draft),Objects.requireNonNull(priorQuality),modelControl);
                default -> Objects.requireNonNull(draft);
            };
            var quality=phase==NotesSynthesisObjects.Phase.CHECK?synthesis.check(material,document,modelControl)
                    :phase==NotesSynthesisObjects.Phase.RECHECK?synthesis.recheck(material,document,modelControl)
                    :phase==NotesSynthesisObjects.Phase.ARBITRATE?synthesis.arbitrate(material,document,Objects.requireNonNull(priorQuality),modelControl):null;
            NotesLeaseRunner.check(control);material.requireMatching(document);processing.requireRoot(root,control);
            var object=checkpoints.stage(root,sourceDigest,phase,document,quality,control);
            boolean review=phase==NotesSynthesisObjects.Phase.CHECK || phase==NotesSynthesisObjects.Phase.RECHECK || phase==NotesSynthesisObjects.Phase.ARBITRATE;
            String ref=(review?"notes-quality:":"notes-draft:")+document.contentDigest();
            String digest=review?MysqlNotesJobStore.qualityReceiptDigest(quality):document.contentDigest();
            if(!notes.stageSynthesisResult(call.id(),lease,ref,digest,object) || !notes.commitCall(call.id(),call.reservedMicros()))throw error("NOTES_CHECKPOINT_CONFLICT");
            return new NotesSynthesisObjects.Checkpoint(1,root.id(),root.generation(),root.planDigest(),sourceDigest,phase,document,quality);
        }catch(RuntimeException failure){if(sent.get())notes.markUnknown(call.id());throw failure;}
    }
    private NotesSynthesisObjects.Checkpoint load(NotesJob.Job root,NotesMaterialReader.Prepared material,String sourceDigest,
            NotesSynthesisObjects.Phase phase,NotesJob.Call call,CallControl control) {
        var ref=notes.synthesisCheckpoint(call.id()).orElseThrow(()->error("NOTES_CHECKPOINT_MISSING"));
        var value=checkpoints.read(root,sourceDigest,phase,ref,control);material.requireMatching(value.document());
        boolean review=phase==NotesSynthesisObjects.Phase.CHECK || phase==NotesSynthesisObjects.Phase.RECHECK || phase==NotesSynthesisObjects.Phase.ARBITRATE;
        String expectedRef=(review?"notes-quality:":"notes-draft:")+value.document().contentDigest();
        String expectedDigest=review?MysqlNotesJobStore.qualityReceiptDigest(value.quality()):value.document().contentDigest();
        if(!expectedRef.equals(call.resultRef()) || !expectedDigest.equals(call.resultDigest())
                || review && !synthesis.qualityPolicyDigest().equals(value.quality().policyDigest()))throw error("NOTES_CHECKPOINT_CONFLICT");
        return value;
    }
    /** Covers crash after SUMMARY completion but before Root READY; no provider calls or fresh reservations. */
    public boolean publishCompleted(NotesJob.Job root,CallControl control) {
        requireRevision(root);processing.requireRoot(root,control);
        var dependency=notes.synthesisDependency(root.id(),root.request().tenantId(),root.request().userId());
        if(dependency.isEmpty() || dependency.get().workId()==null)return false;
        var job=content.get(dependency.get().workId());
        if(job.status()!=ContentWork.Status.SUCCEEDED || job.result()==null)return false;
        var material=materials.read(root.id(),root.request().tenantId(),root.request().userId(),control);
        String sourceDigest=sourceDigest(root,dependency.get().unit(),control);
        List<NotesSynthesisObjects.Checkpoint> loaded=new ArrayList<>();
        for(var phase:List.of(NotesSynthesisObjects.Phase.DRAFT,NotesSynthesisObjects.Phase.CHECK)) {
            var call=notes.call(root.id(),root.request().tenantId(),root.request().userId(),root.generation(),dependency.get().unit().segmentId(root.id())+":"+phase.name().toLowerCase(Locale.ROOT))
                    .filter(c->c.state()==NotesJob.CallState.COMMITTED).orElseThrow(()->error("NOTES_CHECKPOINT_MISSING"));
            loaded.add(load(root,material,sourceDigest,phase,call,control));
        }
        var checked=loaded.get(1);var finalDraft=loaded.get(0);
        if(!checked.quality().passed()) {
            var repaired=new ArrayList<NotesSynthesisObjects.Checkpoint>();
            for(var phase:List.of(NotesSynthesisObjects.Phase.REPAIR,NotesSynthesisObjects.Phase.RECHECK)) {
                var call=notes.call(root.id(),root.request().tenantId(),root.request().userId(),root.generation(),dependency.get().unit().segmentId(root.id())+":"+phase.name().toLowerCase(Locale.ROOT))
                        .filter(c->c.state()==NotesJob.CallState.COMMITTED).orElseThrow(()->error("NOTES_CHECKPOINT_MISSING"));
                repaired.add(load(root,material,sourceDigest,phase,call,control));
            }
            finalDraft=repaired.get(0);checked=repaired.get(1);
            if(!checked.quality().passed()) {
                var phase=NotesSynthesisObjects.Phase.ARBITRATE;
                var call=notes.call(root.id(),root.request().tenantId(),root.request().userId(),root.generation(),dependency.get().unit().segmentId(root.id())+":arbitrate")
                        .filter(c->c.state()==NotesJob.CallState.COMMITTED).orElseThrow(()->error("NOTES_CHECKPOINT_MISSING"));
                checked=load(root,material,sourceDigest,phase,call,control);
            }
        }
        if(!finalDraft.document().contentDigest().equals(checked.document().contentDigest()))throw error("NOTES_SOURCE_CONFLICT");
        var reference=documents.stage(root,material,checked.document(),checked.quality(),synthesis.qualityPolicyDigest(),control);
        if(!job.result().reference().equals(reference.objectKey()) || !job.result().digest().equals(reference.sha256()))throw error("NOTES_CHECKPOINT_CONFLICT");
        return publication.publish(root.id(),root.request().tenantId(),root.request().userId(),material,reference,checked.quality(),job.id(),control);
    }
    private String sourceDigest(NotesJob.Job root,NotesJob.PlanUnit unit,CallControl control) {
        control.checkpoint();String digest=MysqlNotesJobStore.sourceSnapshotDigest(notes.sourceSnapshot(root.id(),root.request().tenantId(),root.request().userId()));
        if(!digest.equals(unit.inputDigest()))throw error("NOTES_SOURCE_CHANGED");return digest;
    }
    private void requireRevision(NotesJob.Job root){if(!root.request().policy().generationVersion().equals(synthesis.generationVersion()))throw error("NOTES_GENERATION_CHANGED");}
    private static AgentException error(String code){return new AgentException(code,code);}
    public void close(){runner.close();}
}
