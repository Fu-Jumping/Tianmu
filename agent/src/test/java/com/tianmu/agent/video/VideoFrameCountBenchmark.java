package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.BailianTranscriptionClient;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit shadow evaluation, never a serving endpoint. */
public final class VideoFrameCountBenchmark {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var cfg=json.readTree(Files.readString(Path.of(args[0])));
        var model=new VideoModelClient(new VideoModelProperties(true,cfg.path("provider").asText(),URI.create(cfg.path("baseUrl").asText()),Files.readString(Path.of(cfg.path("visionKeyFile").asText())).strip(),cfg.path("model").asText(),""),json);
        var asr=BailianTranscriptionClient.create(cfg.path("region").asText(),Files.readString(Path.of(cfg.path("asrKeyFile").asText())).strip(),json,Duration.ofSeconds(60));
        for(var item:json.readTree(Files.readString(Path.of(args[1])))) {
            var original=json.treeToValue(item.get("media"),VideoContent.Media.class);String speech="";String audioStatus="NO_AUDIO";
            if(original.audioBase64()!=null && !original.audioBase64().isBlank())try{speech=asr.transcribeTimed(original.audioBase64(),original.startMs(),original.endMs(),control()).rawText();audioStatus="READY";}
            catch(Exception e){audioStatus="FAILED";}
            for(int count:new int[]{12,6,3}){
                var out=new LinkedHashMap<String,Object>();out.put("fixture",item.path("id").asText());out.put("frames",count);out.put("audioStatus",audioStatus);
                if(audioStatus.equals("FAILED")){out.put("status","AUDIO_FAILED");System.out.println(json.writeValueAsString(out));continue;}
                var selected=new ArrayList<VideoContent.Frame>();for(int i=0;i<count;i++)selected.add(original.frames().get((2*i+1)*12/(2*count)));
                var media=new VideoContent.Media(original.source(),original.startMs(),original.endMs(),selected,original.audioBase64());
                long start=System.nanoTime();try{
                    VideoFactsValidator.media(media.source(),media,media.startMs(),media.endMs());
                    var raw=model.understand(media,VideoAnalysisService.FACT_FOCUS,speech,control());out.put("visualResponse",raw);
                    var facts=VideoFactsValidator.facts(media,raw,speech,cfg.path("model").asText());out.put("status","SUCCEEDED");out.put("summary",facts.summary());out.put("moments",facts.moments());
                }catch(Exception e){out.put("status","FAILED");out.put("errorType",e.getClass().getSimpleName());if(e instanceof com.tianmu.agent.core.AgentException a)out.put("errorCode",a.code());}
                out.put("elapsedMs",(System.nanoTime()-start)/1_000_000);out.put("sampledAtMs",selected.stream().map(VideoContent.Frame::atMs).toList());System.out.println(json.writeValueAsString(out));System.out.flush();
            }
        }
    }
    private static CallControl control(){return new CallControl(Instant.now().plusSeconds(100),new CancellationToken());}
}
