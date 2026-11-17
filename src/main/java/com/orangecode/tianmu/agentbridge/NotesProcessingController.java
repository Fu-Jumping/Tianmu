package com.orangecode.tianmu.agentbridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Internal service-only reauthorization. Never accepts a user identity from the public browser. */
@RestController
@RequestMapping("/internal/agent/notes-processing")
@ConditionalOnProperty(name={"agent.bridge.enabled","agent.content-processing.enabled","agent.content-processing.notes-enabled","agent.content-reading.enabled"},havingValue="true")
public final class NotesProcessingController {
    public record Context(String tenantId,String userId,BusinessContentAuthority.Asset asset,long durationMs) {}
    public record Issue(Context context,UUID requestId) {}
    private final BusinessContentIdentityReader reader;
    private final BusinessContentAuthority authority;
    private final BridgeProperties bridge;
    private final byte[] token;
    public NotesProcessingController(BusinessContentIdentityReader reader, BusinessContentAuthority authority,
                                     ContentProcessingProperties properties,BridgeProperties bridge)throws java.io.IOException {
        this.reader=Objects.requireNonNull(reader);this.authority=Objects.requireNonNull(authority);this.bridge=Objects.requireNonNull(bridge);
        token=Files.readString(Path.of(properties.tokenFile())).strip().getBytes(StandardCharsets.UTF_8);
        if(token.length<32 || token.length>256)throw new IllegalArgumentException("Invalid service token");
    }
    private void require(String credential,Context context) {
        if(credential==null || !MessageDigest.isEqual(token,credential.getBytes(StandardCharsets.UTF_8)))throw new BridgeException("UNAUTHORIZED",401);
        if(context==null || context.asset()==null || context.durationMs()<1)throw new BridgeException("INVALID_CONTENT_REQUEST",400);
        var principal=reader.activeViewer(context.tenantId(),context.userId(),bridge.activeUserStates());
        var identity=reader.readableIdentity(principal,context.asset().videoId());
        if(!identity.asset().equals(context.asset()) || identity.durationMs()!=context.durationMs())throw new BridgeException("VIDEO_VERSION_CHANGED",409);
    }
    @PostMapping("/check") public void check(@RequestHeader(value="X-Content-Processing-Token",required=false)String token,@RequestBody Context request){require(token,request);}
    @PostMapping("/grants") public ResponseEntity<BusinessContentAuthority.Grant> issue(@RequestHeader(value="X-Content-Processing-Token",required=false)String token,@RequestBody Issue request){
        if(request==null || request.requestId()==null)throw new BridgeException("INVALID_CONTENT_REQUEST",400);
        require(token,request.context());var grant=authority.issueNotes(request.requestId(),request.context().asset().videoId(),request.context().userId());
        require(token,request.context());if(!grant.asset().equals(request.context().asset()))throw new BridgeException("VIDEO_VERSION_CHANGED",409);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(grant);
    }
    @ExceptionHandler(BridgeException.class) public ResponseEntity<Map<String,String>> failure(BridgeException error){return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(Map.of("code",error.code()));}
}
