package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpContentProcessingAuthorityTest {
    private static final String TOKEN="local-test-platform-token-32-characters";
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private static CallControl control(){return new CallControl(Instant.now().plusSeconds(10),new CancellationToken());}
    private HttpContentProcessingAuthority client(HttpServer server){return new HttpContentProcessingAuthority(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),TOKEN,Duration.ofSeconds(5),json);}
    @Test void businessServletContextIsRetainedForInternalCalls()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var called=new AtomicBoolean();
        server.createContext("/api/internal/agent/notes-processing/check",exchange->{try(exchange){called.set(true);exchange.sendResponseHeaders(200,-1);}});server.start();
        try {
            var client=new HttpContentProcessingAuthority(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api"),TOKEN,Duration.ofSeconds(5),json);
            client.requireNotes("site","123",new ContentAssetKey("site",7,"v1","public",1),30000,control());assertTrue(called.get());
            assertThrows(IllegalArgumentException.class,()->new HttpContentProcessingAuthority(URI.create("http://127.0.0.1:1/untrusted"),TOKEN,Duration.ofSeconds(5),json));
        }finally{server.stop(0);}
    }
    @Test void grantWireProtocolUsesServiceTokenAndExactStringVideoId()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> id=new AtomicReference<>(),token=new AtomicReference<>();
        server.createContext("/internal/agent/content-processing/grants",exchange->{
            try(exchange){
                var body=json.readTree(exchange.getRequestBody());id.set(body.path("videoId").textValue());token.set(exchange.getRequestHeaders().getFirst("X-Content-Processing-Token"));
                var grant=new ContentWork.Grant(UUID.fromString(body.path("requestId").asText()),new ContentAssetKey("site",800000026087195706L,"v1","public",1),Instant.now().plusSeconds(60));
                byte[] bytes=json.writeValueAsBytes(grant);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }
        });server.start();
        try {
            UUID request=UUID.randomUUID();var grant=client(server).issue(request,800000026087195706L,control());
            assertEquals(request,grant.id());assertEquals("800000026087195706",id.get());assertEquals(TOKEN,token.get());
        }finally{server.stop(0);}
    }
    @Test void redirectsNeverForwardPlatformTokenAndRevocationRemainsTyped()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger status=new AtomicInteger(302),forwarded=new AtomicInteger();
        server.createContext("/internal/agent/content-processing/check",exchange->{try(exchange){exchange.getResponseHeaders().set("Location","/redirected");exchange.sendResponseHeaders(status.get(),-1);}});
        server.createContext("/redirected",exchange->{try(exchange){forwarded.incrementAndGet();exchange.sendResponseHeaders(200,-1);}});
        server.start();
        var grant=new ContentWork.Grant(UUID.randomUUID(),new ContentAssetKey("site",7,"v1","public",1),Instant.now().plusSeconds(60));
        try {
            assertEquals("CONTENT_AUTHORITY_UNAVAILABLE",assertThrows(AgentException.class,()->client(server).require(grant,ContentWork.Checkpoint.DISPATCH,control())).code());
            assertEquals(0,forwarded.get());status.set(403);
            assertEquals("CONTENT_GRANT_REVOKED",assertThrows(AgentException.class,()->client(server).require(grant,ContentWork.Checkpoint.COMMIT,control())).code());
            status.set(422);assertEquals("CONTENT_MEDIA_UNSUPPORTED",assertThrows(AgentException.class,()->client(server).require(grant,ContentWork.Checkpoint.CLAIM,control())).code());
        }finally{server.stop(0);}
    }
    @Test void mediaWithWrongVersionCannotReachTheTranscriber()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/agent/content-processing/media",exchange->{try(exchange){
            byte[] bytes=json.writeValueAsBytes(new VideoContent.Media(new VideoContent.Source(7,"wrong",60000),0,1000,List.of(),"AQID"));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }});server.start();
        var grant=new ContentWork.Grant(UUID.randomUUID(),new ContentAssetKey("site",7,"v1","public",1),Instant.now().plusSeconds(60));
        try{assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->client(server).media(grant,0,1000,control())).code());}
        finally{server.stop(0);}
    }
    @Test void sharedGrantContractPreservesLongIdAsString()throws Exception {
        var file=java.nio.file.Path.of(System.getProperty("basedir"),"../contracts/video-content/grant-v1.json").normalize();
        var expected=json.readTree(java.nio.file.Files.readAllBytes(file));
        var grant=json.treeToValue(expected,ContentWork.Grant.class);
        var writer=json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        assertEquals(expected,writer.readTree(writer.writeValueAsBytes(grant)));
        assertEquals(800000026087195706L,grant.asset().videoId());
    }
    @Test void transcriptionRequestsExplicitAudioOnlyExtraction()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicBoolean audioOnly=new AtomicBoolean();
        server.createContext("/internal/agent/content-processing/media",exchange->{try(exchange){
            audioOnly.set(json.readTree(exchange.getRequestBody()).path("audioOnly").asBoolean());
            byte[] bytes=json.writeValueAsBytes(new VideoContent.Media(new VideoContent.Source(7,"v1",60000),0,1000,List.of(),"AQID"));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }});server.start();
        var grant=new ContentWork.Grant(UUID.randomUUID(),new ContentAssetKey("site",7,"v1","public",1),Instant.now().plusSeconds(60));
        try{assertTrue(client(server).audio(grant,0,1000,control()).frames().isEmpty());assertTrue(audioOnly.get());}
        finally{server.stop(0);}
    }
    @Test void notesAuthorizationBindsOwnerAndRejectsMismatchedMediaGrant()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);AtomicReference<String> seenUser=new AtomicReference<>();AtomicBoolean mismatch=new AtomicBoolean();
        server.createContext("/internal/agent/notes-processing/grants",exchange->{try(exchange){
            var body=json.readTree(exchange.getRequestBody());seenUser.set(body.path("context").path("userId").asText());
            var asset=new ContentAssetKey("site",7,mismatch.get()?"changed":"v1","public",1);
            byte[] result=json.writeValueAsBytes(new ContentWork.Grant(UUID.fromString(body.path("requestId").asText()),asset,Instant.now().plusSeconds(60)));
            exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);
        }});server.start();
        try{var asset=new ContentAssetKey("site",7,"v1","public",1);var id=UUID.randomUUID();
            assertEquals(id,client(server).issueNotes("site","123",asset,30000,id,control()).id());assertEquals("123",seenUser.get());
            mismatch.set(true);assertEquals("INVALID_CONTENT_GRANT",assertThrows(AgentException.class,()->client(server).issueNotes("site","123",asset,30000,id,control())).code());
        }finally{server.stop(0);}
    }
}
