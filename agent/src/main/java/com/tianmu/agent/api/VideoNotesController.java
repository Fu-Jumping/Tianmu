package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.notes.*;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Explicit opt-in only after authorization, background workers, and media-version checks are assembled. */
@RestController
@ConditionalOnProperty(name="agent.video.notes.enabled",havingValue="true")
@RequestMapping("/api/agent/videos/{videoId}/notes")
public final class VideoNotesController {
    public record CreateRequest(String sourceVersion) {}
    public record StatusResponse(UUID jobId,String status,String sourceVersion,long durationMs) {}
    private final IdentityPort identity;
    private final NotesAdmissionService admission;
    private final NotesPublicationService publication;
    private final NotesRetryService retry;
    public VideoNotesController(IdentityPort identity,NotesAdmissionService admission,NotesPublicationService publication,NotesRetryService retry) {
        this.identity=Objects.requireNonNull(identity);this.admission=Objects.requireNonNull(admission);this.publication=Objects.requireNonNull(publication);this.retry=Objects.requireNonNull(retry);
    }
    private AccessScope scope(String authorization,long videoId) {
        if(authorization==null || !authorization.startsWith("Bearer ") || authorization.length()<=7)throw new AgentException("UNAUTHORIZED","Login required");
        if(videoId<=0)throw new AgentException("INVALID_REQUEST","Invalid video ID");
        return identity.forCreate(authorization.substring(7),"",null,new RequestContext(Long.toString(videoId)),null);
    }
    private CallControl control(){return new CallControl(Instant.now().plusSeconds(15),new CancellationToken());}
    private static StatusResponse status(NotesJob.Job job){return new StatusResponse(job.id(),job.status().name(),job.request().asset().sourceVersion(),job.request().durationMs());}
    private static void sameVideo(long videoId,NotesJob.Job job) {if(job.request().asset().videoId()!=videoId)throw new AgentException("NOTES_NOT_FOUND","Notes unavailable");}
    @GetMapping public ResponseEntity<StatusResponse> lookup(@PathVariable long videoId,@RequestHeader(value="Authorization",required=false)String auth) {
        var result=admission.lookup(videoId,scope(auth,videoId),control());
        var response=result.job().map(VideoNotesController::status).orElseGet(()->new StatusResponse(null,"NOT_STARTED",result.identity().asset().sourceVersion(),result.identity().durationMs()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
    @PostMapping public ResponseEntity<StatusResponse> create(@PathVariable long videoId,
            @RequestHeader(value="Authorization",required=false)String auth,@RequestHeader("Idempotency-Key")String key,@RequestBody CreateRequest request) {
        if(request==null)throw new AgentException("INVALID_REQUEST","Playback version required");
        var job=admission.create(videoId,request.sourceVersion(),key,scope(auth,videoId),control());sameVideo(videoId,job);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(status(job));
    }
    @GetMapping("/{jobId}") public ResponseEntity<StatusResponse> current(@PathVariable long videoId,@PathVariable UUID jobId,
            @RequestHeader(value="Authorization",required=false)String auth) {
        var job=admission.current(jobId,scope(auth,videoId),control());sameVideo(videoId,job);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(status(job));
    }
    @PostMapping("/{jobId}/retry") public ResponseEntity<StatusResponse> retry(@PathVariable long videoId,@PathVariable UUID jobId,
            @RequestHeader(value="Authorization",required=false)String auth) {
        var viewer=scope(auth,videoId);var c=control();sameVideo(videoId,admission.current(jobId,viewer,c));
        var result=retry.retry(jobId,viewer,c);sameVideo(videoId,result);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(status(result));
    }
    @GetMapping("/{jobId}/document") public ResponseEntity<NotesDocument> document(@PathVariable long videoId,@PathVariable UUID jobId,
            @RequestHeader(value="Authorization",required=false)String auth) {
        var viewer=scope(auth,videoId);var control=control();sameVideo(videoId,admission.current(jobId,viewer,control));
        var doc=publication.read(jobId,viewer,control);
        if(doc.coverage().asset().videoId()!=videoId)throw new AgentException("NOTES_NOT_FOUND","Notes unavailable");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(doc);
    }
}
