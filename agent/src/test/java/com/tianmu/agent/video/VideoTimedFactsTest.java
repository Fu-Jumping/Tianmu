package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.video.content.TimedTranscript;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VideoTimedFactsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    @Test void keepsActualSegmentTimesAndReadsHistoricalFacts() throws Exception {
        var source = new VideoContent.Source(1, "version", 2000);
        var media = new VideoContent.Media(source, 1000, 2000, List.of(new VideoContent.Frame(1200, "frame")), null);
        var result = json.readTree("{\"summary\":\"板书\",\"moments\":[]}");
        var timed = new TimedTranscript(1000, 2000, "定义域", TimedTranscript.State.AVAILABLE,
                TimedTranscript.Precision.SEGMENT, List.of(new TimedTranscript.Segment(1100, 1500, "定义域")));
        var facts = VideoFactsValidator.facts(media, result, timed.rawText(), "model", timed);
        assertEquals("SEGMENT", facts.transcriptPrecision());
        assertEquals(new VideoContent.TranscriptSegment(1100, 1500, "定义域"), facts.transcriptSegments().get(0));
        assertEquals(facts, json.readValue(json.writeValueAsBytes(facts), VideoContent.Facts.class));
        var old = json.valueToTree(facts);
        ((com.fasterxml.jackson.databind.node.ObjectNode) old).remove(List.of("transcriptPrecision", "transcriptSegments"));
        var restored = json.treeToValue(old, VideoContent.Facts.class);
        assertEquals("CLIP", restored.transcriptPrecision());
        assertTrue(restored.transcriptSegments().isEmpty());
    }
    @Test void rejectsUnanchoredAndInventedClipTimes() {
        assertThrows(IllegalArgumentException.class, () -> new VideoContent.Facts("video-content-v1", 1, "v", 1000, 2000,
                "内容", List.of(), "定义域", "SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT", List.of(1200L), "model", Instant.now(),
                "CLIP", List.of(new VideoContent.TranscriptSegment(1100, 1500, "定义域"))));
        assertThrows(IllegalArgumentException.class, () -> new VideoContent.Facts("video-content-v1", 1, "v", 1000, 2000,
                "内容", List.of(), "定义域", "SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT", List.of(1200L), "model", Instant.now(),
                "SEGMENT", List.of(new VideoContent.TranscriptSegment(900, 1500, "定义域"))));
        assertThrows(IllegalArgumentException.class, () -> new VideoContent.Facts("video-content-v1", 1, "v", 1000, 2000,
                "内容", List.of(), "定义域", "SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT", List.of(1200L), "model", Instant.now(),
                "SEGMENT", List.of()));
    }
    @Test void rejectsTranscriptFromAnotherClipOrWithChangedRawText() throws Exception {
        var source = new VideoContent.Source(1, "v", 3000);
        var media = new VideoContent.Media(source, 1000, 2000, List.of(new VideoContent.Frame(1200, "frame")), null);
        var result = json.readTree("{\"summary\":\"板书\",\"moments\":[]}");
        var other = new TimedTranscript(2000, 3000, "文本", TimedTranscript.State.AVAILABLE,
                TimedTranscript.Precision.CLIP, List.of(new TimedTranscript.Segment(2000, 3000, "文本")));
        assertThrows(AgentException.class, () -> VideoFactsValidator.facts(media, result, "文本", "model", other));
        var timed = new TimedTranscript(1000, 2000, "文本", TimedTranscript.State.AVAILABLE,
                TimedTranscript.Precision.CLIP, List.of(new TimedTranscript.Segment(1000, 2000, "文本")));
        assertThrows(AgentException.class, () -> VideoFactsValidator.facts(media, result, "不同文本", "model", timed));
    }
}
