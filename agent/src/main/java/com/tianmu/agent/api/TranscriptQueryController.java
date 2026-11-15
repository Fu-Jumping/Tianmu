package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.*;

@RestController
@ConditionalOnProperty(name="agent.video.transcript-query.enabled",havingValue="true")
@RequestMapping("/api/agent/videos/{videoId}/transcript")
public final class TranscriptQueryController {
    public record CitationRequest(TranscriptRetrievalService.Request context,String citationId) {}
    private final IdentityPort identity;
    private final TranscriptRetrievalService reader;
    private final TranscriptQueryProperties policy;
    public TranscriptQueryController(IdentityPort identity,TranscriptRetrievalService reader,TranscriptQueryProperties policy){this.identity=identity;this.reader=reader;this.policy=policy;}
    private AccessScope scope(String auth,long video) {
        if(auth==null || !auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Login required");
        if(video<=0)throw new AgentException("INVALID_REQUEST","Invalid video ID");
        return identity.forCreate(auth.substring(7),"",null,new RequestContext(Long.toString(video)),null);
    }
    private CallControl control(){return new CallControl(Instant.now().plusSeconds(policy.requestSeconds()),new CancellationToken());}
    @GetMapping("/identity") public ResponseEntity<ContentReadAuthority.Identity> current(
            @PathVariable long videoId,@RequestHeader(value="Authorization",required=false)String auth) {
        var control=control();return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.identity(videoId,scope(auth,videoId),control));
    }
    @PostMapping("/query") public ResponseEntity<TranscriptRetrievalService.Bundle> query(@PathVariable long videoId,
            @RequestHeader(value="Authorization",required=false)String auth,@RequestBody TranscriptRetrievalService.Request request) {
        var control=control();var scope=scope(auth,videoId);
        if(request==null)throw new AgentException("INVALID_REQUEST","Transcript context required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.query(videoId,scope,request,control));
    }
    @PostMapping("/citation") public ResponseEntity<TranscriptRetrievalService.Excerpt> citation(@PathVariable long videoId,
            @RequestHeader(value="Authorization",required=false)String auth,@RequestBody CitationRequest request) {
        var control=control();var scope=scope(auth,videoId);
        if(request==null || request.context()==null)throw new AgentException("INVALID_REQUEST","Citation context required");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reader.citation(videoId,scope,request.context(),request.citationId(),control));
    }
}
