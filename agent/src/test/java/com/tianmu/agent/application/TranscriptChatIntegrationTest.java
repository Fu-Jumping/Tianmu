package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.harness.*;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.*;
import com.tianmu.agent.video.content.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranscriptChatIntegrationTest {
    static final String CITATION="ts:00000000-0000-0000-0000-000000000001:0:"+"a".repeat(64);
    static final AccessScope VIEWER=new AccessScope("demo","viewer",Set.of(),"viewer-token",Set.of(1001L));
    static TranscriptQueryProperties policy(String version){return new TranscriptQueryProperties(version,"private-results",60000,128,12,8000,4194304,4,15);}
    static final class Recording implements SpanExporter {
        final List<SpanData> spans=new java.util.concurrent.CopyOnWriteArrayList<>();
        public CompletableResultCode export(Collection<SpanData> data){spans.addAll(data);return CompletableResultCode.ofSuccess();}
        public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}
        public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
    }
    static final class Fixture implements AutoCloseable {
        final Recording recording=new Recording();
        final SdkTracerProvider provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recording)).build();
        final TranscriptRetrievalService reader=mock(TranscriptRetrievalService.class);
        final AtomicLong epoch=new AtomicLong(1);
        final AtomicReference<String> source=new AtomicReference<>("source-v1");
        final TranscriptChatService service=new TranscriptChatService(reader,policy("asr-v1"),provider.get("test"));
        final HookDispatcher hooks;
        final TransformDispatcher transforms;
        final RuntimeHarness harness;
        final ToolDispatcher tools;
        final DeliveryService delivery;
        final TaskAssembler assembler;
        final RunState run=new RunState(VIEWER,"解释当前片段");
        Fixture(AgentHook... registered) {
            hooks=new HookDispatcher(List.of(registered),provider.get("test"));
            transforms=new TransformDispatcher(List.of(),provider.get("test"),new ObjectMapper().findAndRegisterModules());
            harness=new RuntimeHarness(provider.get("test"),hooks,transforms);
            when(reader.identity(eq(1001L),eq(VIEWER),any())).thenAnswer(i->identity());
            when(reader.query(eq(1001L),eq(VIEWER),any(),any())).thenAnswer(i->{
                var request=i.getArgument(2,TranscriptRetrievalService.Request.class);
                assertEquals(60000,request.anchorMs());assertNull(request.startMs());assertNull(request.endMs());
                return bundle(TranscriptRetrievalService.Status.READY,true);
            });
            when(reader.citation(eq(1001L),eq(VIEWER),any(),eq(CITATION),any())).thenReturn(excerpt());
            var beans=new StaticListableBeanFactory();beans.addBean("transcript",service);
            var catalog=new ToolCatalog();var skills=new SkillCatalog();var handlers=new ArrayList<AgentTool>();
            for(String name:catalog.all().keySet()) {
                if(name.equals("read_skill"))handlers.add(new ReadSkillTool());
                else if(name.equals("ask_clarification"))handlers.add(new AskClarificationTool());
                else if(name.equals("read_video_transcript"))handlers.add(new ReadVideoTranscriptTool(beans.getBeanProvider(TranscriptChatService.class)));
                else handlers.add(new AgentTool(){public String name(){return name;}public Exposure exposure(){return Exposure.GENERAL;}public ToolResult execute(ToolCall call,ToolContext context){throw new AssertionError("Unexpected tool "+name);}});
            }
            tools=new ToolDispatcher(skills,harness,new ToolRegistry(handlers,catalog,skills));
            delivery=new DeliveryService(harness);ReflectionTestUtils.setField(delivery,"transcriptChat",service);
            assembler=new TaskAssembler(skills,new PromptCatalog(),catalog);ReflectionTestUtils.setField(assembler,"transcriptChat",service);
            run.pageContext(new RequestContext("1001","lesson",60000L));
            var old=UUID.randomUUID();
            run.memory(new MemoryTypes.View(1,"UNSAFE_FUTURE_SUMMARY",List.of(new MemoryTypes.Message(UUID.randomUUID(),old,1,"assistant","UNSAFE_FUTURE_HISTORY",Instant.now())),false,run.pageContext(),null,
                List.of(new MemoryTypes.Persistent(UUID.randomUUID(),1,"VIDEO","1001","FACT","UNSAFE_FUTURE_MEMORY")),List.of(new MemoryTypes.Compaction(UUID.randomUUID(),1,1,1,1,Map.of("text","UNSAFE_FUTURE_COMPACTION")))));
            var spec=new TaskTypes.Specification("video.content",Map.of("video_id",1001L,"start_ms",45000L,"end_ms",75000L),Map.of("start_ms","current_playback_default","end_ms","current_playback_default"));
            run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.message,Emotion.UNKNOWN,Set.of(),Set.of(),Intent.OTHER,spec),null,null));
            run.task(assembler.assemble(run));
        }
        ContentReadAuthority.Identity identity(){return new ContentReadAuthority.Identity(new ContentAssetKey("demo",1001L,source.get(),"public",epoch.get()),120000);}
        TranscriptRetrievalService.Excerpt excerpt(){return new TranscriptRetrievalService.Excerpt(CITATION,"映射的每个元素都有唯一对应的像。",10000,12000,TimedTranscript.Precision.SEGMENT);}
        TranscriptRetrievalService.Bundle bundle(TranscriptRetrievalService.Status status,boolean text){return new TranscriptRetrievalService.Bundle(identity(),TranscriptRetrievalService.Mode.CURRENT,new TranscriptRetrievalService.Range(0,60000),status,text?List.of(excerpt()):List.of(),List.of(new TranscriptRetrievalService.Range(0,60000)),List.of(),List.of());}
        void execute(ModelPort model) {
            var runtime=AgentRuntimeFactory.create(model,tools,harness,new PromptCatalog(),delivery);
            ReflectionTestUtils.setField(runtime,"contextAuthorities",List.of(service));runtime.execute(run);
        }
        ModelPort model(AtomicInteger calls){return context->{
            calls.incrementAndGet();
            assertTrue(context.memory().recent().isEmpty());assertTrue(context.memory().persistent().isEmpty());assertTrue(context.memory().compaction().isEmpty());assertEquals("",context.memory().olderSummary());
            assertFalse(context.availableTools().stream().anyMatch(t->Set.of("analyze_video_content","read_history","read_report","prepare_comment_draft").contains(t.name())));
            if(!context.activeSkills().contains("video-content"))return Decision.tool("read_skill",Map.of("name","video-content"));
            var transcript=context.observations().stream().filter(t->t.tool().equals("read_video_transcript")).findFirst();
            if(transcript.isEmpty())return Decision.tool("read_video_transcript",Map.of("query","映射"));
            return Decision.answer("这里的唯一性是指：一个输入不能同时对应两个不同的输出。",List.of(transcript.get().evidenceId()));
        };}
        public void close(){harness.close();hooks.close();transforms.close();provider.close();}
    }
    @Test void viewerReadsTypedAudioEvidenceAndDeliveryRevalidatesWithRealTraceNodes() {
        try(var f=new Fixture()) {
            var task=(TaskConditions.Transcript)f.run.task().specification().typed();
            assertEquals(0,task.context().startMs());assertEquals(60000,task.context().endMs());assertEquals("CURRENT",task.context().mode());
            var calls=new AtomicInteger();f.execute(f.model(calls));
            assertEquals(Status.SUCCEEDED,f.run.snapshot().status());assertEquals(3,calls.get());
            var evidence=f.run.execution().evidence().snapshot().values().iterator().next();assertNotNull(evidence.transcript());assertNull(evidence.videoContent());
            assertEquals("音频转写",f.run.snapshot().answer().dataNotes().get(0).label());
            verify(f.reader).citation(eq(1001L),eq(VIEWER),any(),eq(CITATION),any());
            assertTrue(f.recording.spans.stream().anyMatch(s->s.getName().equals("video.transcript.read") && "READY".equals(s.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("content.read_status")))));
            assertTrue(f.recording.spans.stream().anyMatch(s->s.getName().equals("video.transcript.citations")));
            var readSpan=f.recording.spans.stream().filter(s->s.getName().equals("video.transcript.read")).findFirst().orElseThrow();
            assertEquals(f.run.snapshot().traceId(),readSpan.getTraceId());assertNotEquals("0".repeat(16),readSpan.getParentSpanId());
            var bound=f.recording.spans.stream().filter(s->s.getName().equals("video.transcript.bind")).findFirst().orElseThrow();
            assertEquals(60000L,bound.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("content.boundary_end_ms")));
            assertEquals("UNPROVEN_DEPENDENCIES_EXCLUDED",bound.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("content.history_policy")));
            var wire=new ObjectMapper().findAndRegisterModules();var restored=assertDoesNotThrow(()->wire.readValue(wire.writeValueAsBytes(evidence),Evidence.class));assertEquals(evidence,restored);
        }
    }
    @Test void absentTranscriptProducesHostGapAnswerWithoutAskingModelToGuess() {
        try(var f=new Fixture()) {
            when(f.reader.query(eq(1001L),eq(VIEWER),any(),any())).thenReturn(f.bundle(TranscriptRetrievalService.Status.NO_TEXT,false));
            var calls=new AtomicInteger();f.execute(f.model(calls));
            assertEquals(Status.SUCCEEDED,f.run.snapshot().status());assertEquals(2,calls.get());
            assertTrue(f.run.snapshot().answer().text().contains("不表示视频没有声音"));
            verify(f.reader,never()).citation(anyLong(),any(),any(),anyString(),any());
        }
    }
    @Test void epochChangeWhilePreModelGateWaitsPreventsProviderDispatch() {
        var reference=new AtomicReference<Fixture>();var gates=new AtomicInteger();
        AgentHook gate=new AgentHook(){public String name(){return "revoke";}public Mode mode(){return Mode.GATE;}public Set<HookPoint> points(){return Set.of(HookPoint.PRE_MODEL);}public void onEvent(HookEvent e){if(gates.incrementAndGet()==3)reference.get().epoch.incrementAndGet();}};
        try(var f=new Fixture(gate)){reference.set(f);var calls=new AtomicInteger();f.execute(f.model(calls));assertEquals(Status.FAILED,f.run.snapshot().status());assertEquals("VIDEO_VERSION_CHANGED",f.run.snapshot().errorCode());assertEquals(2,calls.get());assertNull(f.run.snapshot().answer());}
    }
    @Test void revocationAfterModelAnswerBlocksDeliveryAndStreamingTextIsBuffered() {
        try(var f=new Fixture()) {
            var calls=new AtomicInteger();var base=f.model(calls);
            ModelPort model=new ModelPort(){public Decision decide(ModelContext c){throw new AssertionError();}public Decision decide(ModelContext c,com.tianmu.agent.runtime.control.CallControl control){
                control.beforeDispatch();if(control.textOutput()!=null)control.textOutput().accept("UNVALIDATED_PROVIDER_TEXT");
                var decision=base.decide(c);if(decision.kind()==Kind.ANSWER)f.epoch.incrementAndGet();return decision;
            }};
            f.execute(model);assertEquals(Status.FAILED,f.run.snapshot().status());assertEquals("VIDEO_VERSION_CHANGED",f.run.snapshot().errorCode());assertNull(f.run.snapshot().answer());
            assertFalse(f.run.events().after(0).toString().contains("UNVALIDATED_PROVIDER_TEXT"));
        }
    }
    @Test void alteredCitationCannotReachDeliveredAnswer() {
        try(var f=new Fixture()) {
            when(f.reader.citation(eq(1001L),eq(VIEWER),any(),eq(CITATION),any())).thenReturn(new TranscriptRetrievalService.Excerpt(CITATION,"被修改的内容",10000,12000,TimedTranscript.Precision.SEGMENT));
            f.execute(f.model(new AtomicInteger()));assertEquals(Status.FAILED,f.run.snapshot().status());assertEquals("INVALID_EVIDENCE",f.run.snapshot().errorCode());assertNull(f.run.snapshot().answer());
        }
    }
    @Test void revokedTranscriptCannotEscapeThroughClarificationTerminal() {
        try(var f=new Fixture()) {
            var base=f.model(new AtomicInteger());
            f.execute(context->{var decision=base.decide(context);if(decision.kind()==Kind.ANSWER){f.epoch.incrementAndGet();return Decision.clarify("老师刚才这句话，你想进一步了解哪部分？");}return decision;});
            assertEquals(Status.FAILED,f.run.snapshot().status());assertEquals("VIDEO_VERSION_CHANGED",f.run.snapshot().errorCode());
            assertNull(f.run.snapshot().clarification());assertNull(f.run.snapshot().answer());
        }
    }
    @Test void sourceAndEpochChangesAfterBindingCannotProduceAudioEvidence() {
        try(var f=new Fixture()) {
            var context=((TaskConditions.Transcript)f.run.task().specification().typed()).context();
            var control=new com.tianmu.agent.runtime.control.CallControl(Instant.now().plusSeconds(10),new com.tianmu.agent.runtime.control.CancellationToken());
            f.source.set("source-v2");assertEquals("VIDEO_VERSION_CHANGED",assertThrows(AgentException.class,()->f.service.read(context,"",VIEWER,control)).code());
            f.source.set("source-v1");f.epoch.incrementAndGet();assertEquals("VIDEO_VERSION_CHANGED",assertThrows(AgentException.class,()->f.service.read(context,"",VIEWER,control)).code());
            f.epoch.set(1);
            when(f.reader.query(eq(1001L),eq(VIEWER),any(),any())).thenAnswer(i->{f.epoch.incrementAndGet();return f.bundle(TranscriptRetrievalService.Status.READY,true);});
            assertEquals("VIDEO_VERSION_CHANGED",assertThrows(AgentException.class,()->f.service.read(context,"",VIEWER,control)).code());
            assertTrue(f.run.execution().evidence().snapshot().isEmpty());
        }
    }
    @Test void normalAndCreatorTasksRemainCompatibleAcrossChatToggleAndPolicyChanges() {
        try(var f=new Fixture()) {
            var disabled=new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog());
            for(var spec:List.of(new TaskTypes.Specification("conversation",Map.of(),Map.of()),new TaskTypes.Specification("creator.draft",Map.of("video_id",1001L,"kind","COMMENT"),Map.of()))) {
                var old=new TaskTypes.PreparedTask(f.run.id,f.run.message,f.run.message,f.run.businessDate,"Asia/Shanghai",VIEWER,f.run.preparedRequest(),spec,disabled.resourceVersions(),f.run.task().allowedTools(),f.run.deadline(),16);
                assertDoesNotThrow(()->f.assembler.verify(old));
                var withMarker=new TaskTypes.PreparedTask(old.runId(),old.originalQuery(),old.query(),old.businessDate(),old.timezone(),old.scope(),old.preparation(),old.specification(),f.assembler.resourceVersions(),old.allowedTools(),old.deadline(),old.maxModelCalls());
                assertDoesNotThrow(()->disabled.verify(withMarker));
                ReflectionTestUtils.setField(f.assembler,"transcriptChat",new TranscriptChatService(f.reader,policy("asr-v2"),f.provider.get("test")));
                assertDoesNotThrow(()->f.assembler.verify(withMarker));
            }
        }
    }
    @Test void acceptedSnapshotControlsNewTaskBindingWithoutRewritingCapturedResources() {
        try(var f=new Fixture()) {
            var disabled=new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog());
            for(boolean enabledAtAcceptance:List.of(false,true)) {
                var run=new RunState(VIEWER,"解释当前片段");run.pageContext(f.run.pageContext());run.memory(MemoryTypes.View.empty().withContext(run.pageContext(),null));
                var factory=new com.tianmu.agent.config.ExecutionPolicyFactory(new com.tianmu.agent.config.PolicySnapshotProperties(true),com.tianmu.agent.runtime.control.LoopPolicy.defaults(),com.tianmu.agent.model.ContextPolicy.defaults(),
                        new com.tianmu.agent.preparation.PreparationPolicy(Duration.ofMillis(300),Duration.ofSeconds(8)),com.tianmu.agent.feedback.FeedbackPolicy.defaults(),enabledAtAcceptance?f.assembler:disabled,null);
                var accepted=factory.capture(run.createdAt,null);run.initializePolicy(accepted);
                run.prepared(new Result(Route.HARNESS,f.run.preparedRequest(),null,null));
                var task=f.assembler.assemble(run);
                assertEquals(enabledAtAcceptance?"video.transcript":"video.content",task.specification().type());
                assertEquals(accepted.business().resources(),run.executionPolicy().business().resources());
                assertEquals(task.policyHash(),run.executionPolicy().contentHash());
                if(enabledAtAcceptance){
                    assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->disabled.verify(task)).code());
                }
            }
        }
    }
    @Test void disablingChatOrChangingPinnedPolicyRejectsOldTaskAndDirectDeliveryFailsClosed() {
        try(var f=new Fixture()) {
            var disabled=new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog());
            assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->disabled.verify(f.run.task())).code());
            var changed=new TranscriptChatService(f.reader,policy("asr-v2"),f.provider.get("test"));ReflectionTestUtils.setField(f.assembler,"transcriptChat",changed);
            assertEquals("RESOURCE_VERSION_MISMATCH",assertThrows(AgentException.class,()->f.assembler.verify(f.run.task())).code());
            assertTrue(f.run.start());
            var context=((TaskConditions.Transcript)f.run.task().specification().typed()).context();
            var facts=f.service.read(context,"映射",VIEWER,new com.tianmu.agent.runtime.control.CallControl(Instant.now().plusSeconds(10),new com.tianmu.agent.runtime.control.CancellationToken()));
            var evidence=new BusinessEvidencePolicy().registerTranscriptEvidence(f.run,facts);
            // Bypass the orchestrator: Delivery must independently reject a disabled authority.
            var direct=new DeliveryService(f.harness);var answer=new Answer("受证据支持的解释",List.of(evidence.id()));
            var reservation=direct.reserve(f.run);
            assertEquals("CONTENT_RESULT_UNAVAILABLE",assertThrows(AgentException.class,()->direct.answer(f.run,answer,reservation,StopReason.ANSWER)).code());
            assertNull(f.run.snapshot().answer());
        }
    }
    @Test void naturalFollowupCannotReloadUnprovenHistoryOrSilentlyGuess() {
        try(var f=new Fixture()) {
            var followup=new RunState(VIEWER,"再举一个例子");followup.pageContext(new RequestContext("1001","lesson",70000L));
            followup.memory(new MemoryTypes.View(1,"FUTURE_SUMMARY",List.of(new MemoryTypes.Message(UUID.randomUUID(),f.run.id,1,"assistant","FUTURE_ANSWER",Instant.now())),false,followup.pageContext(),new MemoryTypes.Focus(f.run.message,f.run.id,"1001",f.run.task().specification())));
            f.service.project(followup);assertTrue(f.service.needsNewAnchor(followup));assertTrue(followup.memory().recent().isEmpty());assertEquals("",followup.memory().olderSummary());
            var contexts=new ContextLifecycle(null,f.harness);ReflectionTestUtils.setField(contexts,"transcriptChat",f.service);
            var response=contexts.memoryRequest(followup);assertTrue(response.isPresent());assertTrue(response.get().text().contains("播放区间"));assertTrue(response.get().evidenceIds().isEmpty());
        }
    }
    @Test void modelCannotExpandTaskOrForgeEvidenceType() {
        try(var f=new Fixture()) {
            assertFalse(AnalysisExecutionPolicy.permits("analyze_video_content",f.run.task().specification()));assertFalse(AnalysisExecutionPolicy.permits("read_history",f.run.task().specification()));
            assertFalse(AnalysisExecutionPolicy.permits("read_video_transcript",new TaskTypes.Specification("conversation",Map.of(),Map.of())));
            var schema=new ToolCatalog().all().get("read_video_transcript");assertFalse(ToolArguments.inspect(new ToolCall("read_video_transcript",Map.of("end_ms",120000)),schema).valid());
            var c=((TaskConditions.Transcript)f.run.task().specification().typed()).context();
            var wrong=new TranscriptContent.Context(c.tenantId(),c.videoId(),c.sourceVersion(),c.domain(),c.epoch(),c.durationMs(),c.stageVersion(),c.mode(),0,90000);
            var forged=new TranscriptContent.Facts(wrong,"NO_TEXT",new TranscriptContent.Range(0,90000),List.of(),List.of(),List.of(),List.of(),Instant.now());
            assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->new BusinessEvidencePolicy().registerTranscriptEvidence(f.run,forged)).code());
        }
    }
}
