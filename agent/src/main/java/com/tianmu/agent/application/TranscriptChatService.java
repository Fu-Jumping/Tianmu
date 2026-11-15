package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.video.content.*;
import com.tianmu.agent.harness.trace.TraceAttributes;
import io.opentelemetry.api.trace.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Opt-in bridge from frozen business tasks to independently authorized audio evidence. */
@Component
@ConditionalOnProperty(name="agent.video.transcript-chat.enabled",havingValue="true")
public final class TranscriptChatService implements com.tianmu.agent.runtime.spi.ContextAuthority {
    private final TranscriptRetrievalService reader;
    private final TranscriptQueryProperties policy;
    private final Tracer tracer;
    public TranscriptChatService(TranscriptRetrievalService reader,TranscriptQueryProperties policy,Tracer tracer){this.reader=reader;this.policy=policy;this.tracer=tracer;}
    public String revision(){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("transcript-chat-v1:"+policy).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public boolean protects(RunState run) {
        return run.task()!=null && run.task().specification().typed() instanceof TaskConditions.Transcript
                || run.pageContext()!=null || com.tianmu.agent.preparation.VideoContentTask.requested(run.message)
                || run.memory().focus()!=null && ("video.content".equals(run.memory().focus().task()==null?null:run.memory().focus().task().type()) || "video.transcript".equals(run.memory().focus().task()==null?null:run.memory().focus().task().type()));
    }
    /** Old history has no content dependency proof. Do not load it into this bounded task. */
    public void project(RunState run) {
        var memory=run.memory();
        var previous=memory.focus();
        var focus=previous!=null && previous.sourceRunId().equals(run.id)?previous:null;
        boolean priorVideo=previous!=null && (previous.task()!=null && Set.of("video.content","video.transcript","video.transcript.followup").contains(previous.task().type())
                || com.tianmu.agent.preparation.VideoContentTask.requested(previous.request()));
        if(focus==null && priorVideo && !com.tianmu.agent.preparation.VideoContentTask.requested(run.message))
            focus=new MemoryTypes.Focus("请重新指定要解释的片段",previous.sourceRunId(),previous.referenceVideoId(),new TaskTypes.Specification("video.transcript.followup",Map.of(),Map.of()));
        int removed=memory.recent().size()+memory.persistent().size()+memory.compaction().size();
        run.memory(new MemoryTypes.View(memory.version(),"",List.of(),true,memory.environment(),focus,List.of(),List.of()));
        TraceAttributes.apply(Span.current(),Map.of("content.history_excluded",removed,"content.history_policy","UNPROVEN_DEPENDENCIES_EXCLUDED"));
    }
    public boolean needsNewAnchor(RunState run) {
        var focus=run.memory().focus();
        return focus!=null && focus.task()!=null && focus.task().type().equals("video.transcript.followup")
            && !com.tianmu.agent.preparation.VideoContentTask.requested(run.message)
            && (ConversationFocus.continuation(run.message) || run.message.matches("(?s).*(?:这个|这里|刚才|上面|例子|换.*说法|再.*讲|详细一点).*"));
    }
    @Override public boolean permitsStreaming(RunState run){return run.task()==null || !(run.task().specification().typed() instanceof TaskConditions.Transcript);}
    public static boolean eligible(TaskTypes.Specification spec) {
        return "video.content".equals(spec.type()) && !spec.reading().reportRequired()
                && (spec.conditions().containsKey("start_ms") || spec.provenance().containsValue("current_playback_default"));
    }
    public TaskTypes.Specification bind(RunState run,TaskTypes.Specification spec) {
        if(!eligible(spec))return spec;
        var video=(TaskConditions.Video)spec.typed();
        boolean current=spec.provenance().containsValue("current_playback_default");
        // Whole-video summaries keep their existing path until a bounded full-coverage reader exists.
        if(!current && video.startMs()==null)return spec;
        return traced("video.transcript.bind",()->{
            var identity=reader.identity(video.videoId(),run.owner,control(run));var asset=identity.asset();
            var player=run.memory().environment();
            if(player==null || !player.videoId().equals(Long.toString(video.videoId())) || player.sourceVersion()==null
                    || !player.sourceVersion().equals(asset.sourceVersion()))
                throw new AgentException("VIDEO_VERSION_CHANGED","Player media version is unavailable or changed; refresh the video");
            long from=video.startMs(),to=video.endMs();
            if(current) {
                var page=run.memory().environment();
                if(page==null || page.playbackMs()==null || !page.videoId().equals(Long.toString(video.videoId())))throw invalid("Frozen playback anchor is unavailable");
                from=0;to=page.playbackMs();
            }
            var context=new TranscriptContent.Context(asset.tenantId(),asset.videoId(),asset.sourceVersion(),asset.authorizationDomain(),asset.revocationEpoch(),identity.durationMs(),policy.stageVersion(),current?"CURRENT":"INTERVAL",from,to);
            var values=new LinkedHashMap<String,Object>();values.put("tenant_id",context.tenantId());values.put("video_id",context.videoId());
            values.put("source_version",context.sourceVersion());values.put("domain",context.domain());values.put("epoch",context.epoch());values.put("duration_ms",context.durationMs());
            values.put("stage_version",context.stageVersion());values.put("mode",context.mode());values.put("start_ms",context.startMs());values.put("end_ms",context.endMs());
            var provenance=new HashMap<String,String>(spec.provenance());
            values.keySet().forEach(k->provenance.putIfAbsent(k,"server_content_authority"));
            provenance.put("start_ms",current?"strict_anchor_boundary":"user_input");provenance.put("end_ms",current?"page_playback_snapshot":"user_input");
            attributes(context);
            project(run);
            return new TaskTypes.Specification("video.transcript",values,provenance,new AnalysisContract(Set.of(AnalysisContract.Source.VIDEO),false));
        });
    }
    public TranscriptContent.Facts read(TranscriptContent.Context context,String query,AccessScope viewer,CallControl caller) {
        Instant until=Instant.now().plusSeconds(policy.requestSeconds());if(until.isAfter(caller.deadline()))until=caller.deadline();
        var control=new CallControl(until,caller.cancellation(),caller.budget(),caller.externalCallStarted(),caller.textOutput(),caller.dispatchBarrier(),caller.frozenContext());
        return traced("video.transcript.read",()->{
            requireIdentity(context,reader.identity(context.videoId(),viewer,control));
            var bundle=reader.query(context.videoId(),viewer,request(context,query),control);
            requireIdentity(context,bundle.identity());
            var facts=new TranscriptContent.Facts(context,bundle.status().name(),range(bundle.requested()),
                    bundle.excerpts().stream().map(e->new TranscriptContent.Fragment(e.citationId(),e.text(),e.startMs(),e.endMs(),e.precision().name())).toList(),
                    bundle.processedRanges().stream().map(TranscriptChatService::range).toList(),bundle.missingRanges().stream().map(TranscriptChatService::range).toList(),bundle.limitations(),Instant.now());
            attributes(context);TraceAttributes.apply(Span.current(),Map.of("content.read_status",facts.status(),"content.excerpt_count",facts.fragments().size(),"content.gap_count",facts.missingRanges().size(),"content.limitations",facts.limitations()));
            return facts;
        });
    }
    public void verify(RunState run) {
        if(run.task()==null || !(run.task().specification().typed() instanceof TaskConditions.Transcript transcript))return;
        traced("video.transcript.authorize",()->{requireIdentity(transcript.context(),reader.identity(transcript.context().videoId(),run.owner,control(run)));attributes(transcript.context());return true;});
        var m=run.memory();
        if(!m.recent().isEmpty() || !m.olderSummary().isEmpty() || !m.persistent().isEmpty() || !m.compaction().isEmpty())throw invalid("Unproven historical content is outside this playback boundary");
        for(var evidence:run.execution().evidence().snapshot().values())if(evidence.transcript()!=null && !evidence.transcript().context().equals(transcript.context()))throw invalid("Evidence changed the frozen playback context");
    }
    public void verifyDelivery(RunState run,Answer answer) {
        if(run.task()==null || !(run.task().specification().typed() instanceof TaskConditions.Transcript transcript))return;
        verify(run);
        traced("video.transcript.citations",()->{
            var control=control(run);
            for(String id:new LinkedHashSet<>(answer.evidenceIds())) {
                var evidence=run.execution().evidence().get(id);
                if(evidence==null || evidence.transcript()==null || !evidence.transcript().context().equals(transcript.context()))throw invalid("Answer lacks frozen audio evidence");
                for(var fragment:evidence.transcript().fragments()) {
                    var live=reader.citation(transcript.context().videoId(),run.owner,request(transcript.context(),""),fragment.citationId(),control);
                    if(!live.text().equals(fragment.text()) || live.startMs()!=fragment.startMs() || live.endMs()!=fragment.endMs() || !live.precision().name().equals(fragment.precision()))throw invalid("Citation content changed");
                }
            }
            requireIdentity(transcript.context(),reader.identity(transcript.context().videoId(),run.owner,control));return true;
        });
    }
    private CallControl control(RunState run) {
        Instant deadline=Instant.now().plusSeconds(policy.requestSeconds());if(deadline.isAfter(run.deadline()))deadline=run.deadline();
        return run.callControl(deadline,run.cancellation());
    }
    private void requireIdentity(TranscriptContent.Context context,ContentReadAuthority.Identity identity) {
        var a=identity.asset();
        if(!policy.stageVersion().equals(context.stageVersion()) || !a.tenantId().equals(context.tenantId()) || a.videoId()!=context.videoId() || !a.sourceVersion().equals(context.sourceVersion())
                || !a.authorizationDomain().equals(context.domain()) || a.revocationEpoch()!=context.epoch() || identity.durationMs()!=context.durationMs())
            throw new AgentException("VIDEO_VERSION_CHANGED","Frozen transcript authority changed; start a new request");
    }
    private static TranscriptContent.Range range(TranscriptRetrievalService.Range r){return new TranscriptContent.Range(r.startMs(),r.endMs());}
    private static TranscriptRetrievalService.Request request(TranscriptContent.Context c,String query){return new TranscriptRetrievalService.Request(c.sourceVersion(),TranscriptRetrievalService.Mode.valueOf(c.mode()),"CURRENT".equals(c.mode())?c.endMs():null,"INTERVAL".equals(c.mode())?c.startMs():null,"INTERVAL".equals(c.mode())?c.endMs():null,query);}
    private static AgentException invalid(String message){return new AgentException("INVALID_EVIDENCE",message);}
    private static void attributes(TranscriptContent.Context c){TraceAttributes.apply(Span.current(),Map.of("content.mode",c.mode(),"content.boundary_start_ms",c.startMs(),"content.boundary_end_ms",c.endMs(),"content.source_version",c.sourceVersion(),"content.epoch",c.epoch(),"content.stage_version",c.stageVersion()));}
    private <T>T traced(String name,Supplier<T> action) {
        var span=tracer.spanBuilder(name).startSpan();com.tianmu.agent.harness.trace.TraceNode.begin(span,name);
        try(var scope=span.makeCurrent()){var value=action.get();TraceAttributes.apply(span,Map.of("node.status","SUCCEEDED"));return value;}
        catch(RuntimeException error){span.setStatus(StatusCode.ERROR);TraceAttributes.apply(span,Map.of("node.status","FAILED","error.code",error instanceof AgentException e?e.code():"INTERNAL_ERROR"));throw error;}
        finally{span.end();}
    }
}
