package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.video.TranscriptionPort;
import com.tianmu.agent.runtime.control.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TimedTranscriptTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void convertsRelativeSecondsToPlaybackTimeWithConservativeBounds() throws Exception {
        var response = json.readTree("{\"text\":\"映射的定义\",\"segments\":[{\"start\":0.1234,\"end\":1.5678,\"text\":\"映射的定义\"}]}");
        var result = TimedTranscript.fromRelativeSeconds(response, 300000, 330000);
        assertEquals(300123, result.segments().get(0).startMs());
        assertEquals(301568, result.segments().get(0).endMs());
        assertEquals(TimedTranscript.Precision.SEGMENT, result.precision());
        assertEquals("映射的定义", result.rawText());
        assertEquals(result, json.readValue(json.writeValueAsBytes(result), TimedTranscript.class));
    }
    @Test void textOnlyProviderHasClipCoverageAndEmptyResponseDoesNotProveSilence() throws Exception {
        var result = TimedTranscript.fromRelativeSeconds(json.readTree("{\"text\":\"一句话\"}"), 1000, 2000);
        assertEquals(TimedTranscript.Precision.CLIP, result.precision());
        assertEquals(List.of(new TimedTranscript.Segment(1000, 2000, "一句话")), result.segments());
        var empty = TimedTranscript.fromRelativeSeconds(json.readTree("{\"text\":\"\",\"segments\":[]}"), 1000, 2000);
        assertEquals(TimedTranscript.State.NO_TRANSCRIPT, empty.state());
        assertTrue(empty.segments().isEmpty());
    }
    @Test void refusesOutOfRangeNegativeUnorderedAndMalformedProviderTimes() throws Exception {
        for (String segments : List.of(
                "[{\"start\":-0.001,\"end\":1,\"text\":\"a\"}]",
                "[{\"start\":0,\"end\":31,\"text\":\"a\"}]",
                "[{\"start\":2,\"end\":3,\"text\":\"a\"},{\"start\":0,\"end\":1,\"text\":\"b\"}]",
                "[{\"start\":\"0\",\"end\":1,\"text\":\"a\"}]")) {
            var response = json.readTree("{\"segments\":" + segments + "}");
            assertThrows(IllegalArgumentException.class, () -> TimedTranscript.fromRelativeSeconds(response, 300000, 330000));
        }
        assertThrows(IllegalArgumentException.class, () -> new TimedTranscript(0, 30000, "text",
                TimedTranscript.State.AVAILABLE, TimedTranscript.Precision.CLIP,
                List.of(new TimedTranscript.Segment(100, 200, "text"))));
    }
    @Test void existingTranscriptionAdaptersRemainCompatibleWithoutInventingTiming() {
        TranscriptionPort provider = (audio, control) -> "原始识别内容";
        var control = new CallControl(Instant.now().plusSeconds(10), new CancellationToken());
        var result = provider.transcribeTimed("audio", 5000, 15000, control);
        assertEquals(TimedTranscript.Precision.CLIP, result.precision());
        assertEquals(5000, result.segments().get(0).startMs());
        assertEquals(15000, result.segments().get(0).endMs());
        assertEquals("原始识别内容", result.rawText());
    }
}
