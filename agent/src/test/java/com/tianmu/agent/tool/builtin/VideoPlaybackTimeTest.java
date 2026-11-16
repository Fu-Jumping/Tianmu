package com.tianmu.agent.tool.builtin;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VideoPlaybackTimeTest {
    @Test void reusedAndFreshClipsAreRegisteredSeparately(){
        var old=new com.tianmu.agent.core.VideoContent.Facts("video-content-v1",123,"v1",270000,300000,"旧片段",java.util.List.of(),"","SAMPLED_FRAMES",java.util.List.of(271250L),"model",java.time.Instant.now());
        var tail=new com.tianmu.agent.core.VideoContent.Facts("video-content-v1",123,"v1",299000,305000,"新片段",java.util.List.of(),"","SAMPLED_FRAMES",java.util.List.of(301000L),"model",java.time.Instant.now());
        var context=mock(com.tianmu.agent.tool.ToolContext.class);
        var video=mock(com.tianmu.agent.video.VideoAnalysisService.class);when(video.reuseEnabled()).thenReturn(true);
        when(context.currentVideoWindow()).thenReturn(java.util.Optional.of(new com.tianmu.agent.application.CurrentVideoWindow(123,275000,305000,215000)));
        when(context.videoQuery(any())).thenReturn(java.util.List.of(old,tail));
        when(context.registerVideoEvidence(old)).thenReturn(new com.tianmu.agent.core.AgentTypes.Evidence("old",java.util.UUID.randomUUID(),null,null,null,old));
        when(context.registerVideoEvidence(tail)).thenReturn(new com.tianmu.agent.core.AgentTypes.Evidence("tail",java.util.UUID.randomUUID(),null,null,null,tail));
        var result=new AnalyzeVideoContentTool(video,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()).execute(new com.tianmu.agent.core.AgentTypes.ToolCall("analyze_video_content",java.util.Map.of("video_id",123)),context);
        assertEquals(java.util.List.of("old","tail"),result.allEvidenceIds());
        assertEquals(2,((java.util.List<?>)result.data().get("materials")).size());
        verify(context).checkVideoQuery(123,270000L,300000L);verify(context).checkVideoQuery(123,299000L,305000L);
    }
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
    @Test void missingBoundsUseFrozenInitialWindowRatherThanEntireVideo() {
        var facts=new com.tianmu.agent.core.VideoContent.Facts("video-content-v1",123,"v1",270000,300000,"板书",
                java.util.List.of(),"","SAMPLED_FRAMES",java.util.List.of(271250L),"model",java.time.Instant.now());
        var context=mock(com.tianmu.agent.tool.ToolContext.class);
        when(context.currentVideoWindow()).thenReturn(java.util.Optional.of(new com.tianmu.agent.application.CurrentVideoWindow(123,270000,300000,210000)));
        when(context.videoQuery(any())).thenReturn(facts);
        when(context.registerVideoEvidence(facts)).thenReturn(new com.tianmu.agent.core.AgentTypes.Evidence("ev1",java.util.UUID.randomUUID(),null,null,null,facts));
        var tool=new AnalyzeVideoContentTool(null,new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        var result=tool.execute(new com.tianmu.agent.core.AgentTypes.ToolCall("analyze_video_content",java.util.Map.of("video_id",123)),context);
        verify(context).checkVideoQuery(123,270000L,300000L);
        assertEquals(java.util.Map.of("start_ms",210000L,"end_ms",270000L),result.data().get("available_prior_range"));
    }
}
