package com.orangecode.tianmu.agentbridge;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/internal/agent/content-processing")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name={"agent.bridge.enabled","agent.content-processing.enabled"},havingValue="true")
public final class ContentProcessingController {
    private final BusinessContentAuthority authority;
    private final byte[] token;
    @org.springframework.beans.factory.annotation.Autowired
    public ContentProcessingController(BusinessContentAuthority authority,ContentProcessingProperties properties)throws java.io.IOException {
        this(authority,java.nio.file.Files.readString(java.nio.file.Path.of(properties.tokenFile())).strip().getBytes(StandardCharsets.UTF_8));
    }
    public ContentProcessingController(BusinessContentAuthority authority,byte[] token) {
        if(token==null || token.length<32 || token.length>256)throw new IllegalArgumentException("Invalid content processing service token");
        for(byte value:token)if(value<0x21 || value>0x7e)throw new IllegalArgumentException("Service token must be printable ASCII");
        this.authority=Objects.requireNonNull(authority);this.token=token.clone();
    }
    public record Issue(UUID requestId,String videoId) {}
    public enum Checkpoint { ADMISSION,CLAIM,DISPATCH,COMMIT }
    public record Check(BusinessContentAuthority.Grant grant,Checkpoint checkpoint) {}
    public record Read(BusinessContentAuthority.Grant grant,long startMs,long endMs) {}
    private void auth(String supplied) {
        if(supplied==null || supplied.length()>256 || !MessageDigest.isEqual(token,supplied.getBytes(StandardCharsets.UTF_8)))
            throw new BridgeException("UNAUTHORIZED",401);
    }
    @PostMapping("/grants") public ResponseEntity<BusinessContentAuthority.Grant> issue(
            @RequestHeader(value="X-Content-Processing-Token",required=false)String token,@RequestBody Issue request) {
        auth(token);
        if(request==null || request.videoId()==null || !request.videoId().matches("[1-9][0-9]{0,18}"))throw new BridgeException("INVALID_CONTENT_REQUEST",400);
        long video;
        try { video=Long.parseLong(request.videoId()); }catch(NumberFormatException e){throw new BridgeException("INVALID_CONTENT_REQUEST",400);}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authority.issue(request.requestId(),video));
    }
    @PostMapping("/check") public void check(@RequestHeader(value="X-Content-Processing-Token",required=false)String token,@RequestBody Check request) {
        auth(token);if(request==null || request.checkpoint()==null)throw new BridgeException("INVALID_CONTENT_REQUEST",400);
        authority.require(request.grant());
    }
    @PostMapping("/media") public ResponseEntity<BusinessVideoContent.Media> read(
            @RequestHeader(value="X-Content-Processing-Token",required=false)String token,@RequestBody Read request) {
        auth(token);
        if(request==null || request.startMs()<0 || request.endMs()<=request.startMs()
                || request.endMs()-request.startMs()>120000)throw new BridgeException("INVALID_PLAYBACK_RANGE",400);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authority.read(request.grant(),request.startMs(),request.endMs()));
    }
    @PostMapping("/grants/{id}/revoke") public void revoke(@RequestHeader(value="X-Content-Processing-Token",required=false)String token,@PathVariable UUID id) {
        auth(token);authority.revoke(id);
    }
    @ExceptionHandler(BridgeException.class) public ResponseEntity<Map<String,String>> failure(BridgeException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(Map.of("code",error.code()));
    }
}
