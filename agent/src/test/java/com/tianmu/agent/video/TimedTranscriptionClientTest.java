package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.TimedTranscript;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TimedTranscriptionClientTest {
    private static CallControl control() { return new CallControl(Instant.now().plusSeconds(10), new CancellationToken()); }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static HttpClient http(String output) {
        var http = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(output.getBytes(StandardCharsets.UTF_8));
        doReturn(CompletableFuture.completedFuture(response)).when(http).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        return http;
    }
    private static VideoModelClient client(HttpClient http) {
        return new VideoModelClient(new VideoModelProperties(true, "compatible", URI.create("https://asr.example"),
                "test-key", "vision", "speech"), new ObjectMapper(), http);
    }
    private static String audio() { return Base64.getEncoder().encodeToString(new byte[]{1,2,3}); }

    @Test void timedAdapterPreservesProviderSegmentsAndLegacyTextAdapterStillWorks() {
        var client = client(http("{\"text\":\"定义域\",\"segments\":[{\"start\":0.5,\"end\":2.25,\"text\":\"定义域\"}]}"));
        var result = client.transcribeTimed(audio(), 300000, 330000, control());
        assertEquals(300500, result.segments().get(0).startMs());
        assertEquals(302250, result.segments().get(0).endMs());
        assertEquals("定义域", client.transcribe(audio(), control()));
    }
    @Test void textOnlyResponseRetainsHonestClipPrecision() {
        var result = client(http("{\"text\":\"定义域\"}")).transcribeTimed(audio(), 300000, 330000, control());
        assertEquals(TimedTranscript.Precision.CLIP, result.precision());
        assertEquals(300000, result.segments().get(0).startMs());
        assertEquals(330000, result.segments().get(0).endMs());
    }
    @Test void invalidTimestampsFailRatherThanBeingClampedToTheCurrentPosition() {
        var http = http("{\"text\":\"定义域\",\"segments\":[{\"start\":0,\"end\":31,\"text\":\"定义域\"}]}");
        var error = assertThrows(AgentException.class, () -> client(http).transcribeTimed(audio(), 300000, 330000, control()));
        assertEquals("INVALID_TRANSCRIPT_TIMING", error.code());
        verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
    @Test void invalidClipAndCancelledRequestNeverDispatch() {
        var http = http("{}"); var client = client(http);
        assertThrows(IllegalArgumentException.class, () -> client.transcribeTimed(audio(), 1000, 1000, control()));
        var token = new CancellationToken(); token.cancel(StopReason.USER_CANCELLED);
        assertThrows(RuntimeException.class, () -> client.transcribeTimed(audio(), 0, 1000,
                new CallControl(Instant.now().plusSeconds(10), token)));
        verifyNoInteractions(http);
    }
}
