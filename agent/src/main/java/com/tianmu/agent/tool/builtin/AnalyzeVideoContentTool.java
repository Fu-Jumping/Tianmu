package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.video.*;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Locale;

@Component
public final class AnalyzeVideoContentTool implements AgentTool {
    private final VideoAnalysisService video;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public AnalyzeVideoContentTool(VideoAnalysisService video, com.fasterxml.jackson.databind.ObjectMapper json) { this.video=video; this.json=json; }
    public String name() { return "analyze_video_content"; }
    public boolean batchable() { return true; }
    public boolean parallelSafe(){return true;}
    public ToolResult execute(ToolCall call, ToolContext context) {
        long id=ToolArguments.integer(call,"video_id");
        Long start=call.arguments().containsKey("start_ms")?ToolArguments.integer(call,"start_ms"):null;
        Long end=call.arguments().containsKey("end_ms")?ToolArguments.integer(call,"end_ms"):null;
        var window=context.currentVideoWindow();
        if(start==null && end==null && window.isPresent()) { start=window.get().initialStartMs(); end=window.get().endMs(); }
        final Long selectedStart=start, selectedEnd=end;
        context.checkVideoQuery(id,selectedStart,selectedEnd);
        String focus=call.arguments().containsKey("focus")?ToolArguments.text(call,"focus"):"";
        if(video!=null && video.reuseEnabled() && window.isPresent()
                && selectedStart!=null && selectedStart==window.get().initialStartMs() && selectedEnd==window.get().endMs()){
            var parts=context.videoQuery(control->video.analyzeCurrent(id,selectedStart,selectedEnd,window.get().allowedStartMs(),focus,context.scope(),control));
            var materials=new java.util.ArrayList<Map<String,Object>>();var ids=new java.util.ArrayList<String>();
            for(var part:parts){
                context.checkVideoQuery(id,part.startMs(),part.endMs());var e=context.registerVideoEvidence(part);ids.add(e.id());
                materials.add(Map.of("analysis",json.convertValue(part,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),"evidence_id",e.id(),
                        "playback_range",Map.of("start",playbackTime(part.startMs()),"end",playbackTime(part.endMs()))));
            }
            var data=new java.util.LinkedHashMap<String,Object>();data.put("materials",materials);data.put("question",focus);
            data.put("limitations","Each material retains its complete original source range and precision. Overlapping clips can repeat spoken text; do not treat repetitions as multiple events or invent sentence timestamps.");
            long earliest=parts.stream().mapToLong(p->p.startMs()).min().orElseThrow();
            if(earliest>window.get().allowedStartMs())data.put("available_prior_range",Map.of("start_ms",window.get().allowedStartMs(),"end_ms",earliest));
            return new ToolResult(name(),data,ids.get(0),null,null,null,ids.subList(1,ids.size()));
        }
        var facts=context.videoQuery(control -> video.analyze(id,selectedStart,selectedEnd,focus,context.scope(),control));
        Evidence evidence=context.registerVideoEvidence(facts);
        Map<String,Object> content=json.convertValue(facts,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        var playback=Map.of("start",playbackTime(facts.startMs()),"end",playbackTime(facts.endMs()));
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("analysis",content); result.put("playback_range",playback); result.put("evidence_id",evidence.id());
        result.put("question",focus); result.put("limitations","Generic sampled-frame facts are reused across questions. Answer the question using these facts; do not infer unseen details. Playback time is distinct from creation time. If stating the analyzed range, copy playback_range exactly; do not convert millisecond values yourself. Sample timestamps are not exact event times.");
        if (window.isPresent() && window.get().videoId()==id && facts.startMs()>window.get().allowedStartMs())
            result.put("available_prior_range", Map.of("start_ms",window.get().allowedStartMs(),"end_ms",facts.startMs()));
        return new ToolResult(name(),result,evidence.id());
    }
    static String playbackTime(long milliseconds) {
        if(milliseconds<0)throw new IllegalArgumentException("Invalid playback time");
        long seconds=milliseconds/1000, hours=seconds/3600, minutes=seconds/60%60, remaining=seconds%60;
        String time=hours>0?String.format(Locale.ROOT,"%d:%02d:%02d",hours,minutes,remaining)
                :String.format(Locale.ROOT,"%02d:%02d",minutes,remaining);
        return milliseconds%1000==0?time:time+String.format(Locale.ROOT,".%03d",milliseconds%1000);
    }
}
