package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.resource.BoundedHttpBody;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit service-to-service adapter. No viewer token, model URL or redirected credential forwarding. */
public final class HttpContentProcessingAuthority implements ContentProcessingAuthority,ContentProcessingMediaPort {
    private final URI base;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration timeout;
    public HttpContentProcessingAuthority(URI base,String token,Duration timeout,ObjectMapper json) {
        if(base==null || !Set.of("http","https").contains(base.getScheme()) || base.getHost()==null
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || !Set.of("","/","/api","/api/").contains(base.getPath()) || token==null || !token.matches("[\\x21-\\x7e]{32,256}")
                || timeout==null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60))>0)
            throw new IllegalArgumentException("Invalid content authority transport");
        this.base=URI.create(base.toString().replaceAll("/+$",""));this.token=token;this.timeout=timeout;
        this.json=json.copy().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.http=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public ContentWork.Grant issue(UUID requestId,long videoId,CallControl control) {
        if(requestId==null || videoId<=0)throw new IllegalArgumentException("Invalid grant request");
        ContentWork.Grant grant=post("/grants",Map.of("requestId",requestId,"videoId",Long.toString(videoId)),ContentWork.Grant.class,control);
        if(!requestId.equals(grant.id()) || grant.asset().videoId()!=videoId || !"public".equals(grant.asset().authorizationDomain()))
            throw new AgentException("INVALID_CONTENT_GRANT","Invalid platform media grant");
        return grant;
    }
    /** Trusted background context comes from the persisted server-owned notes Root. */
    public void requireNotes(String tenant,String user,ContentAssetKey asset,long duration,CallControl control) {
        postTo("/internal/agent/notes-processing/check",Map.of("tenantId",tenant,"userId",user,"asset",asset,"durationMs",duration),Void.class,control);
    }
    public ContentWork.Grant issueNotes(String tenant,String user,ContentAssetKey asset,long duration,UUID requestId,CallControl control) {
        var context=Map.of("tenantId",tenant,"userId",user,"asset",asset,"durationMs",duration);
        var grant=postTo("/internal/agent/notes-processing/grants",Map.of("context",context,"requestId",requestId),ContentWork.Grant.class,control);
        if(grant==null || !requestId.equals(grant.id()) || !asset.equals(grant.asset()))throw new AgentException("INVALID_CONTENT_GRANT","Notes grant differs from frozen media");
        return grant;
    }
    public void require(ContentWork.Grant grant,ContentWork.Checkpoint checkpoint,CallControl control) {
        post("/check",Map.of("grant",grant,"checkpoint",checkpoint.name()),Void.class,control);
    }
    public VideoContent.Media media(ContentWork.Grant grant,long startMs,long endMs,CallControl control) {
        return readMedia(grant,startMs,endMs,false,control);
    }
    @Override public VideoContent.Media audio(ContentWork.Grant grant,long startMs,long endMs,CallControl control) {
        return readMedia(grant,startMs,endMs,true,control);
    }
    private VideoContent.Media readMedia(ContentWork.Grant grant,long startMs,long endMs,boolean audioOnly,CallControl control) {
        if(startMs<0 || endMs<=startMs || endMs-startMs>120000)throw new IllegalArgumentException("Invalid media range");
        var result=post("/media",Map.of("grant",grant,"startMs",startMs,"endMs",endMs,"audioOnly",audioOnly),VideoContent.Media.class,control);
        if(result==null || result.source()==null || result.source().videoId()!=grant.asset().videoId()
                || !grant.asset().sourceVersion().equals(result.source().sourceVersion())
                || result.startMs()!=startMs || result.endMs()!=endMs || result.source().durationMs()<endMs
                || result.audioBase64()==null || result.frames()==null || result.frames().size()>12 || audioOnly && !result.frames().isEmpty())
            throw new AgentException("INVALID_EVIDENCE","Invalid platform media response");
        return result;
    }
    private <T> T post(String path,Object body,Class<T> type,CallControl control) {
        return postTo("/internal/agent/content-processing"+path,body,type,control);
    }
    private <T> T postTo(String path,Object body,Class<T> type,CallControl control) {
        control.checkpoint();
        long millis=Math.min(timeout.toMillis(),Duration.between(Instant.now(),control.deadline()).toMillis());
        if(millis<=0)throw new AgentException("DEADLINE_EXCEEDED","Content authority deadline exceeded");
        try {
            var request=HttpRequest.newBuilder(URI.create(base+path))
                    .timeout(Duration.ofMillis(millis)).header("X-Content-Processing-Token",token)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var future=http.sendAsync(request,ignored->new BoundedHttpBody(8_000_000));
            try(var registration=control.cancellation().onCancel(()->future.cancel(true))) {
                HttpResponse<byte[]> response;
                try { response=future.get(millis,TimeUnit.MILLISECONDS); }
                catch(Exception failure){future.cancel(true);throw failure;}
                control.checkpoint();
                if(response.statusCode()==403)throw new AgentException("CONTENT_GRANT_REVOKED","Platform media permission revoked");
                if(response.statusCode()==409)throw new AgentException("VIDEO_VERSION_CHANGED","Platform media version changed");
                if(response.statusCode()==422)throw new AgentException("CONTENT_MEDIA_UNSUPPORTED","Platform media is outside supported limits");
                if(response.statusCode()==400)throw new AgentException("INVALID_CONTENT_REQUEST","Invalid platform content request");
                if(response.statusCode()<200 || response.statusCode()>=300)
                    throw new AgentException("CONTENT_AUTHORITY_UNAVAILABLE","Content authority unavailable");
                return type==Void.class?null:json.readValue(response.body(),type);
            }
        }catch(AgentException e){throw e;}
        catch(Exception failure){control.checkpoint();throw new AgentException("CONTENT_AUTHORITY_UNAVAILABLE","Content authority transport unavailable");}
    }
}
