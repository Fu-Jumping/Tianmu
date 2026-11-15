package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Private durable files for explicit single-node/shared-volume assembly; not a download endpoint. */
public final class FileContentTranscriptStore implements ContentTranscriptStore {
    private final Path root;
    private final ObjectMapper json;
    public FileContentTranscriptStore(Path root,ObjectMapper json)throws java.io.IOException {
        this.root=root.toAbsolutePath().normalize();Files.createDirectories(this.root);
        this.json=json.copy().findAndRegisterModules()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public ContentWork.Receipt save(ContentWork.Job job,ContentWork.Lease lease,TimedTranscript transcript) {
        if(!job.id().equals(lease.jobId()) || job.fence()!=lease.fence() || !Objects.equals(job.leaseOwner(),lease.worker())
                || transcript.clipStartMs()!=job.unit().startMs() || transcript.clipEndMs()!=job.unit().endMs())
            throw new IllegalArgumentException("Transcript does not belong to this work generation");
        Path temp=null;
        try {
            byte[] bytes=json.writeValueAsBytes(Map.of("schemaVersion","content-transcript-v1","unit",job.unit(),"transcript",transcript));
            if(bytes.length>2_000_000)throw new AgentException("CONTENT_RESULT_TOO_LARGE","Content result too large");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Path target=root.resolve(job.id()+"-"+lease.fence()+".json");
            if(Files.exists(target)) {
                same(target,bytes);
            } else {
                temp=Files.createTempFile(root,"content-",".tmp");Files.write(temp,bytes);
                try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}
                // Atomic create-if-absent: ATOMIC_MOVE may replace an existing target on some providers.
                try { Files.createLink(target,temp); }
                catch(FileAlreadyExistsException race){same(target,bytes);}
                Files.deleteIfExists(temp);temp=null;
            }
            return new ContentWork.Receipt("content:"+job.id()+":"+lease.fence(),digest);
        }catch(AgentException e){throw e;}
        catch(Exception e){throw new AgentException("CONTENT_RESULT_UNAVAILABLE","Content result storage unavailable");}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    private static void same(Path file,byte[] expected)throws java.io.IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>2_000_000
                || !Arrays.equals(Files.readAllBytes(file),expected))
            throw new AgentException("CONTENT_RESULT_CONFLICT","Stored content result differs");
    }
    public void verify(ContentWork.Job job,ContentWork.Receipt receipt) { read(job,receipt,2_000_000); }
    public ContentTranscriptStore.Loaded read(ContentWork.Job job,ContentWork.Receipt receipt,int maxBytes) {
        if(maxBytes<1)throw new AgentException("CONTENT_READ_LIMIT","Content read budget exhausted");
        int limit=Math.min(2_000_000,maxBytes);
        try {
            String prefix="content:"+job.id()+":";
            if(!receipt.reference().startsWith(prefix))throw new IllegalArgumentException();
            String fence=receipt.reference().substring(prefix.length());
            if(!fence.matches("[1-9][0-9]{0,17}") || Long.parseLong(fence)>job.fence())throw new IllegalArgumentException();
            Path file=root.resolve(job.id()+"-"+fence+".json");
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>2_000_000)throw new IllegalArgumentException();
            byte[] bytes;
            try(var stream=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){bytes=stream.readNBytes(limit+1);}
            if(bytes.length>limit)throw new AgentException("CONTENT_READ_LIMIT","Content read budget exhausted");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if(!receipt.digest().equals(digest))throw new IllegalArgumentException();
            var value=json.readTree(bytes);
            if(!"content-transcript-v1".equals(value.path("schemaVersion").asText())
                    || !job.unit().equals(json.treeToValue(value.path("unit"),ContentWork.Unit.class)))throw new IllegalArgumentException();
            TimedTranscript transcript=json.treeToValue(value.path("transcript"),TimedTranscript.class);
            if(transcript.clipStartMs()!=job.unit().startMs() || transcript.clipEndMs()!=job.unit().endMs())throw new IllegalArgumentException();
            return new ContentTranscriptStore.Loaded(transcript,bytes.length);
        }catch(AgentException limitReached){throw limitReached;}
        catch(Exception invalid){throw new AgentException("CONTENT_RESULT_UNAVAILABLE","Stored transcript cannot be verified");}
    }
}
