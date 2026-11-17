package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.store.ObjectStore;
import java.util.*;

/** Private draft/check checkpoints; metadata is persisted atomically with the phase receipt. */
public final class NotesSynthesisObjects {
    public enum Phase { DRAFT, CHECK, REPAIR, RECHECK, ARBITRATE }
    public record Reference(String key,long bytes,String sha256) {
        public Reference {
            if(key==null || !key.matches("notes-step-[a-f0-9]{64}\\.json") || bytes<1 || bytes>1048576)throw new IllegalArgumentException("Invalid checkpoint reference");
            NotesJob.digest(sha256);
        }
    }
    public record Checkpoint(int schemaVersion,UUID rootId,int generation,String planDigest,String sourceDigest,Phase phase,
                             NotesDocument document,NotesDocument.QualityReceipt quality) {
        public Checkpoint {
            if(schemaVersion!=1 || generation<1)throw new IllegalArgumentException("Invalid checkpoint schema");
            Objects.requireNonNull(rootId);Objects.requireNonNull(phase);Objects.requireNonNull(document);
            NotesJob.digest(planDigest);NotesJob.digest(sourceDigest);
            boolean review=phase==Phase.CHECK || phase==Phase.RECHECK || phase==Phase.ARBITRATE;
            if(!review && quality!=null || review && (quality==null || !quality.documentDigest().equals(document.contentDigest())))
                throw new IllegalArgumentException("Invalid checkpoint content");
        }
    }
    private final ObjectStore objects;
    private final ObjectMapper json;
    public NotesSynthesisObjects(ObjectStore objects,ObjectMapper json){
        this.objects=Objects.requireNonNull(objects);this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public Reference stage(NotesJob.Job root,String sourceDigest,Phase phase,NotesDocument document,NotesDocument.QualityReceipt quality,CallControl control) {
        if(!root.request().asset().equals(document.coverage().asset()) || root.request().durationMs()!=document.coverage().durationMs())throw new IllegalArgumentException("Checkpoint media mismatch");
        var value=new Checkpoint(1,root.id(),root.generation(),root.planDigest(),sourceDigest,phase,document,quality);
        try{
            byte[] body=json.writeValueAsBytes(value);String sha=NotesObjectIO.sha(body);
            String key="notes-step-"+NotesJob.hash(root.id().toString(),Integer.toString(root.generation()),sourceDigest,phase.name(),sha)+".json";
            var reference=new Reference(key,body.length,sha);NotesObjectIO.put(objects,key,body,control);return reference;
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid synthesis checkpoint",invalid);}
    }
    public Checkpoint read(NotesJob.Job root,String sourceDigest,Phase phase,Reference reference,CallControl control) {
        NotesLeaseRunner.check(control);
        try{
            byte[] body=objects.get(reference.key(),reference.bytes(),reference.sha256());
            if(body.length!=reference.bytes() || !NotesObjectIO.sha(body).equals(reference.sha256()))throw new IllegalArgumentException("Checkpoint integrity mismatch");
            var value=json.readValue(body,Checkpoint.class);
            if(!root.id().equals(value.rootId()) || root.generation()!=value.generation() || !Objects.equals(root.planDigest(),value.planDigest())
                    || !sourceDigest.equals(value.sourceDigest()) || phase!=value.phase() || !root.request().asset().equals(value.document().coverage().asset())
                    || root.request().durationMs()!=value.document().coverage().durationMs())throw new IllegalArgumentException("Checkpoint identity mismatch");
            NotesLeaseRunner.check(control);return value;
        }catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid synthesis checkpoint",invalid);}
    }
}
