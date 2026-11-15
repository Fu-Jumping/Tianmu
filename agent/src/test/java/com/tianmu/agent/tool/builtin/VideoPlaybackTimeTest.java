package com.tianmu.agent.tool.builtin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoPlaybackTimeTest {
    @Test void convertsMillisecondsWithoutLosingFractionOrHours() {
        assertEquals("04:30",AnalyzeVideoContentTool.playbackTime(270000));
        assertEquals("05:00",AnalyzeVideoContentTool.playbackTime(300000));
        assertEquals("00:00.001",AnalyzeVideoContentTool.playbackTime(1));
        assertEquals("1:00:00.125",AnalyzeVideoContentTool.playbackTime(3600125));
        assertThrows(IllegalArgumentException.class,()->AnalyzeVideoContentTool.playbackTime(-1));
    }
    @Test void toolExposesFormattedRangeAlongsideOriginalEvidence() {
        var facts=new com.tianmu.agent.core.VideoContent.Facts("video-content-v1",123,"v1",270000,300000,"板书",
                java.util.List.of(),"","SAMPLED_FRAMES",java.util.List.of(271250L),"model",java.time.Instant.now());
        var context=mock(com.tianmu.agent.tool.ToolContext.class);
        when(context.videoQuery(any())).thenReturn(facts);
        when(context.registerVideoEvidence(facts)).thenReturn(new com.tianmu.agent.core.AgentTypes.Evidence("ev1",java.util.UUID.randomUUID(),null,null,null,facts));
        var tool=new AnalyzeVideoContentTool(null,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        var result=tool.execute(new com.tianmu.agent.core.AgentTypes.ToolCall("analyze_video_content",java.util.Map.of("video_id",123)),context);
        assertEquals(java.util.Map.of("start","04:30","end","05:00"),result.data().get("playback_range"));
        var analysis=(java.util.Map<?,?>)result.data().get("analysis");
        assertEquals(270000L,((Number)analysis.get("startMs")).longValue());
        assertEquals(300000L,((Number)analysis.get("endMs")).longValue());
    }
}
