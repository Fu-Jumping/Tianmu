package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BailianTranscriptionClientTest {
    @Test void sendsNativeRequestAndAlignsResponse() throws Exception {
        var mapper=new ObjectMapper();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();
        server.createContext("/asr",exchange->{
            calls.incrementAndGet();
            var body=mapper.readTree(exchange.getRequestBody());
            assertEquals("Bearer test-key",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals(BailianTranscriptionClient.MODEL,body.path("model").asText());
            assertTrue(body.at("/input/messages/0/content/0/input_audio/data").asText().startsWith("data:audio/wav;base64,"));
            byte[] response="{\"output\":{\"output\":{\"sentence\":{\"sentence_end\":true,\"text\":\"映射\",\"begin_time\":200,\"end_time\":1000,\"words\":[{\"text\":\"映射\",\"begin_time\":200,\"end_time\":1000}]}}}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var client=new BailianTranscriptionClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/asr"),"test-key",mapper,Duration.ofSeconds(5),HttpClient.newHttpClient());
            var barriers=new AtomicInteger();
            var control=new CallControl(Instant.now().plusSeconds(10),new CancellationToken()).withDispatchBarrier(barriers::incrementAndGet);
            var result=client.transcribeTimed(wav(),5000,6000,control);
            assertEquals("映射",result.rawText());
            assertEquals(5200,result.segments().get(0).startMs());
            assertEquals(1,barriers.get()); assertEquals(1,calls.get());
            assertThrows(AgentException.class,()->client.transcribeTimed(wav(),5000,7000,control));
            assertEquals(1,calls.get());
        } finally { server.stop(0); }
    }

    @Test void rejectsAuthorizationErrorsWithoutRetryClassificationOrSecretLeak() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/asr",exchange->{
            byte[] response="provider detail test-key".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(403,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var client=new BailianTranscriptionClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/asr"),"test-key",new ObjectMapper(),Duration.ofSeconds(5),HttpClient.newHttpClient());
            var control=new CallControl(Instant.now().plusSeconds(10),new CancellationToken());
            var error=assertThrows(AgentException.class,()->client.transcribeTimed(wav(),0,1000,control));
            assertEquals("VIDEO_TRANSCRIPTION_REJECTED",error.code());
            assertFalse(error.toString().contains("test-key"));
        } finally { server.stop(0); }
    }
    @Test void timeoutPathsHaveSameNonRetryableOutcome() {
        for(boolean httpTimeout:new boolean[]{true,false}) {
            var http=mock(HttpClient.class);
            var future=new java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<byte[]>>();
            if(httpTimeout)future.completeExceptionally(new java.net.http.HttpTimeoutException("timeout"));
            doReturn(future).when(http).sendAsync(any(),any());
            var client=new BailianTranscriptionClient(URI.create("http://127.0.0.1/asr"),"test-key",new ObjectMapper(),Duration.ofMillis(200),http);
            var control=new CallControl(Instant.now().plusSeconds(5),new CancellationToken());
            var error=assertThrows(AgentException.class,()->client.transcribeTimed(wav(),0,1000,control));
            assertEquals("VIDEO_MODEL_TIMEOUT",error.code());
        }
    }
    @Test void malformedAudioIsNotAProviderFailure() {
        var client=new BailianTranscriptionClient(URI.create("http://127.0.0.1/asr"),"test-key",new ObjectMapper(),Duration.ofSeconds(1),mock(HttpClient.class));
        var control=new CallControl(Instant.now().plusSeconds(5),new CancellationToken());
        assertEquals("CONTENT_AUDIO_UNAVAILABLE",assertThrows(AgentException.class,()->client.transcribeTimed("not base64!",0,1000,control)).code());
        assertEquals("CONTENT_AUDIO_UNAVAILABLE",assertThrows(AgentException.class,()->client.transcribeTimed(wav(),0,2000,control)).code());
    }

    private static String wav() {
        byte[] bytes=new byte[32044];
        var b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bytes.length-8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        b.putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(32000);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
