package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@ConditionalOnProperty(name={"agent.bridge.enabled","agent.content-reading.enabled"},havingValue="true")
@RequestMapping("/internal/agent")
public final class ContentReadBridgeController {
    private final BusinessAuthentication authentication;
    private final BusinessContentIdentityReader authority;
    public ContentReadBridgeController(BusinessAuthentication authentication,BusinessContentIdentityReader authority){this.authentication=authentication;this.authority=authority;}
    @GetMapping("/content-identity") public ResponseEntity<BusinessContentIdentityReader.Identity> identity(
            @RequestHeader(value="Authorization",required=false)String bearer,@RequestParam long videoId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authority.readableIdentity(authentication.authenticate(bearer),videoId));
    }
    @ExceptionHandler(BridgeException.class) public ResponseEntity<Map<String,String>> failure(BridgeException failure){
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore()).body(Map.of("code",failure.code()));
    }
}
