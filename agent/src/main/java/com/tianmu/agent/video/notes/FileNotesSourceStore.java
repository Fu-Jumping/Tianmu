package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.video.content.ContentWork;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Explicit single-node/shared-private-volume assembly. No public URLs or directory listing. */
public final class FileNotesSourceStore implements NotesSourceStore {
    private final Path root;
    private final ObjectMapper json;
    public FileNotesSourceStore(Path root,ObjectMapper json) throws java.io.IOException {
        this.root=root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public ContentWork.Receipt save(ContentWork.Job job,ContentWork.Lease lease,NotesSourceProcessor.Evidence evidence) {
        if (!job.id().equals(lease.jobId()) || job.fence()!=lease.fence() || !Objects.equals(job.leaseOwner(),lease.worker())
                || !job.unit().equals(evidence.unit())) throw new IllegalArgumentException("Source owner mismatch");
        Path temp=null;
        try {
            byte[] bytes=json.writeValueAsBytes(evidence);
            if (bytes.length>2000000) throw new IllegalArgumentException("Source evidence too large");
            String digest=digest(bytes); Path target=root.resolve(job.id()+"-"+lease.fence()+".json");
            temp=Files.createTempFile(root,"notes-",".tmp"); Files.write(temp,bytes);
            try(var file=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)) { file.force(true); }
            try { Files.createLink(target,temp); }
            catch(FileAlreadyExistsException race) {
                if (!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS) || Files.size(target)>2000000
                        || !Arrays.equals(Files.readAllBytes(target),bytes)) throw new IllegalArgumentException("Immutable evidence conflict");
            }
            return new ContentWork.Receipt("notes-source:"+job.id()+":"+lease.fence(),digest);
        } catch(java.io.IOException failure) { throw new IllegalStateException("Notes evidence storage failed",failure); }
        finally { if(temp!=null) try {Files.deleteIfExists(temp);}catch(java.io.IOException ignored) {} }
    }
    public NotesSourceProcessor.Evidence read(ContentWork.Job job,ContentWork.Receipt receipt) {
        String prefix="notes-source:"+job.id()+":";
        if (!receipt.reference().startsWith(prefix)) throw new IllegalArgumentException("Foreign source reference");
        String value=receipt.reference().substring(prefix.length());
        if (!value.matches("[1-9][0-9]{0,17}") || Long.parseLong(value)>job.fence()) throw new IllegalArgumentException("Invalid source fence");
        Path path=root.resolve(job.id()+"-"+value+".json");
        try {
            if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("No private source object");
            byte[] bytes;
            try(var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(2000001); }
            if (bytes.length>2000000 || !receipt.digest().equals(digest(bytes))) throw new IllegalArgumentException("Source digest mismatch");
            var evidence=json.readValue(bytes,NotesSourceProcessor.Evidence.class);
            if (!evidence.unit().equals(job.unit())) throw new IllegalArgumentException("Source identity mismatch");
            return evidence;
        } catch(java.io.IOException failure) { throw new IllegalStateException("Notes source read failed",failure); }
    }
    private static String digest(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
