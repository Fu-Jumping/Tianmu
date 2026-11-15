package com.tianmu.agent.harness;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.CallControl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CurrentSegmentDemoTest {
    final long id=800000026087195706L;
    TaskTypes.Specification resolve(String text,Long position,long duration) {
        return TaskMeaning.defaults().resolve(text,LocalDate.of(2026,11,15),Instant.parse("2026-11-15T06:00:00Z"),
                MemoryTypes.View.empty().withContext(new RequestContext(""+id,"lesson",position),null),duration);
    }
    @Test void shortcutFreezesThirtySecondsBeforeTheRealPlaybackPosition() {
        var s=resolve("解释这个视频当前播放位置附近的画面。",300000L,1799964);
        assertEquals("video.content",s.type());assertEquals(id,s.conditions().get("video_id"));
        assertEquals(270000L,s.conditions().get("start_ms"));assertEquals(300000L,s.conditions().get("end_ms"));
        assertEquals(270000L,s.reading().videoStartMs());assertEquals("current_playback_default",s.provenance().get("start_ms"));
    }
    @Test void clipsAtVideoEdgesAndPreservesExplicitBounds() {
        for(String text:List.of("请解释当前播放位置附近黑板上的例题。","解释一下这里"))
            assertEquals(270000L,resolve(text,300000L,1799964).conditions().get("start_ms"));
        assertEquals("AMBIGUOUS_ANALYSIS_SOURCES",assertThrows(AgentException.class,()->resolve("解释当前片段的画面",0L,1799964)).code());
        assertEquals(0L,resolve("解释当前片段的画面",1000L,1799964).conditions().get("start_ms"));
        assertEquals(1799000L,resolve("解释当前片段的画面",1799000L,1799964).conditions().get("end_ms"));
        var explicit=resolve("解释这个视频当前片段的画面，05:00到05:30",600000L,1799964);
        assertEquals(300000L,explicit.conditions().get("start_ms"));assertEquals(330000L,explicit.conditions().get("end_ms"));
    }
    @Test void missingPlaybackRequiresSpecificClarificationAndGeneralKnowledgeStaysGeneral() {
        var e=assertThrows(AgentException.class,()->resolve("解释当前片段的画面",null,1799964));
        assertEquals("AMBIGUOUS_ANALYSIS_SOURCES",e.code());assertTrue(e.getMessage().contains("播放时间点"));
        assertEquals("unclassified",resolve("解释什么是映射",300000L,1799964).type());
    }
    @Test void realMergeDoesNotReplaceValidPageContextWithGenericClarification() {
        var owner=new AccessScope("demo","alice",Set.of(),"",Set.of(id));
        String query="解释这个视频当前播放位置附近的画面。";
        try(var f=new HarnessLifecycleTest.Fixture();var service=new PreparationService((i,c)->Optional.empty(),new PreparationFlowTest.Model(){
            public IntentRecognition recognizeIntent(Input i,CallControl c){return new IntentRecognition(Intent.OTHER,"video-content",List.of(),i.intentCandidates());}
            public Understanding understand(Input i,CallControl c){return Understanding.proceed(Map.of("video_id",id),null);}
        },new PreparationPolicy(Duration.ofSeconds(1),Duration.ofSeconds(2)),f.harness)) {
            ReflectionTestUtils.setField(service,"contextResolver",new com.tianmu.agent.application.PreparationContextResolver(
                    (video,scope)->new com.tianmu.agent.query.VideoPagePort.Page(""+id,"lesson",1799964),(com.tianmu.agent.query.VideoSearchPort)null));
            var run=new RunState(owner,query);run.pageContext(new RequestContext(""+id,"",300000L));
            var out=new java.util.concurrent.atomic.AtomicReference<Result>();
            f.harness.execute(run,()->{run.start();out.set(service.prepare(run,query));});
            assertEquals(Route.HARNESS,out.get().route());assertEquals(query,out.get().request().query());
            assertEquals(270000L,out.get().request().specification().conditions().get("start_ms"));
            assertEquals("NONE",out.get().request().resolution().clarificationReason());
        }
    }
}
