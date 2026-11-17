package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.store.ObjectStore;
import java.security.MessageDigest;
import java.util.*;

/** Notes-specific envelope over the existing private ObjectStore; no storage transport of its own. */
public final class NotesDocumentObjects {
    private static final int MAX_BYTES=1048576; // existing ObjectStore protocol limit
    public record Reference(String objectKey,long bytes,String sha256,String documentDigest) {
        public Reference {
            if(objectKey==null || !objectKey.matches("notes-[a-f0-9]{64}\\.json") || bytes<1 || bytes>MAX_BYTES)
                throw new IllegalArgumentException("Invalid private notes reference");
            NotesJob.digest(sha256); NotesJob.digest(documentDigest);
        }
    }
    public record Envelope(int schemaVersion,UUID rootId,int generation,String planDigest,NotesDocument document,
                           NotesDocument.QualityReceipt quality,String qualityPolicyDigest) {}
    private final ObjectStore objects;
    private final ObjectMapper json;
    public NotesDocumentObjects(ObjectStore objects,ObjectMapper json) {
        this.objects=Objects.requireNonNull(objects);
        this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public Reference stage(NotesJob.Job root,NotesMaterialReader.Prepared material,NotesDocument document,
                           NotesDocument.QualityReceipt quality,String qualityPolicyDigest,CallControl storageControl) {
        storageControl.checkpoint();
        if(!root.id().equals(material.rootId()) || root.generation()!=material.generation() || root.version()!=material.version()
                || !Objects.equals(root.planDigest(),material.planDigest()) || root.status()!=NotesJob.Status.RUNNING)
            throw new IllegalArgumentException("Stale notes material snapshot");
        material.requireMatching(document); document.requirePublishable(quality,qualityPolicyDigest);
        if(!root.request().asset().equals(document.coverage().asset()) || root.request().durationMs()!=document.coverage().durationMs())
            throw new IllegalArgumentException("Document media mismatch");
        byte[] body=encode(new Envelope(1,root.id(),root.generation(),root.planDigest(),document,quality,qualityPolicyDigest));
        if(body.length>MAX_BYTES) throw new IllegalArgumentException("Document exceeds private object limit; do not truncate");
        String sha=sha(body); String key="notes-"+NotesJob.hash(root.id().toString(),Integer.toString(root.generation()),root.planDigest(),sha)+".json";
        var reference=new Reference(key,body.length,sha,document.contentDigest());
        NotesObjectIO.put(objects,key,body,storageControl);
        storageControl.checkpoint(); return reference;
    }
    /** Caller obtains reference from the authoritative publication row and reauthorizes the reader. */
    public NotesDocument read(NotesJob.Job root,Reference reference,CallControl control) {
        return read(root,reference,null,control);
    }
    public NotesDocument read(NotesJob.Job root,Reference reference,String expectedQualityPolicyDigest,CallControl control) {
        control.checkpoint();
        try {
            byte[] body=objects.get(reference.objectKey(),reference.bytes(),reference.sha256());
            if(body.length!=reference.bytes() || !sha(body).equals(reference.sha256())) throw new IllegalArgumentException("Object integrity failure");
            var envelope=json.readValue(body,Envelope.class);
            if(envelope.schemaVersion()!=1 || !root.id().equals(envelope.rootId()) || root.generation()!=envelope.generation()
                    || !Objects.equals(root.planDigest(),envelope.planDigest()) || !root.request().asset().equals(envelope.document().coverage().asset())
                    || root.request().durationMs()!=envelope.document().coverage().durationMs()
                    || !reference.documentDigest().equals(envelope.document().contentDigest())) throw new IllegalArgumentException("Foreign document envelope");
            envelope.document().requirePublishable(envelope.quality(),envelope.qualityPolicyDigest());
            if(expectedQualityPolicyDigest!=null && !expectedQualityPolicyDigest.equals(envelope.qualityPolicyDigest()))
                throw new IllegalArgumentException("Stored quality policy mismatch");
            control.checkpoint(); return envelope.document();
        } catch(java.io.IOException invalid) {throw new IllegalArgumentException("Invalid notes object",invalid);}
    }
    private byte[] encode(Object value) {
        try {return json.writeValueAsBytes(value);}catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid notes object",invalid);}
    }
    private static String sha(byte[] body) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
