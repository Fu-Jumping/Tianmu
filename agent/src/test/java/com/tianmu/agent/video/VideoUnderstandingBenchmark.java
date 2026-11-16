package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.harness.trace.TraceUsage;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.BailianTranscriptionClient;
import io.opentelemetry.context.Context;
import java.nio.file.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit shadow CLI, not an automatically executed test or application endpoint. */
public final class VideoUnderstandingBenchmark {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var config=json.readTree(Files.readString(Path.of(args[0])));
        var inputs=json.readTree(Files.readString(Path.of(args[1])));
        var model=new VideoModelClient(new VideoModelProperties(true,config.path("provider").asText(),
                URI.create(config.path("baseUrl").asText()),Files.readString(Path.of(config.path("visionKeyFile").asText())).strip(),
                config.path("model").asText(),""),json);
        var audio=BailianTranscriptionClient.create(config.path("region").asText(),
                Files.readString(Path.of(config.path("asrKeyFile").asText())).strip(),json,Duration.ofSeconds(60));
        for(var input:inputs) {
            var media=json.treeToValue(input.get("media"),VideoContent.Media.class);
            VideoFactsValidator.media(media.source(),media,media.startMs(),media.endMs());
            for(boolean parallel:args.length>2 && "reverse".equals(args[2])?new boolean[]{true,false}:new boolean[]{false,true}) {
                var usage=new TraceUsage();var audioMs=new AtomicLong();var visualMs=new AtomicLong();long begin=System.nanoTime();
                var output=new LinkedHashMap<String,Object>();output.put("fixture",input.path("id").asText());output.put("parallel",parallel);
                try(var pipeline=new VideoUnderstanding(new VideoUnderstandingPolicy(parallel,2));
                    var scope=Context.current().with(TraceUsage.KEY,usage).makeCurrent()) {
                    var control=new CallControl(Instant.now().plusSeconds(100),new CancellationToken());
                    var result=pipeline.understand(control,c->{long t=System.nanoTime();try{return audio.transcribeTimed(media.audioBase64(),media.startMs(),media.endMs(),c);}finally{audioMs.set((System.nanoTime()-t)/1_000_000);}},
                            (speech,c)->{long t=System.nanoTime();try{return model.understand(media,VideoAnalysisService.FACT_FOCUS,speech,c);}finally{visualMs.set((System.nanoTime()-t)/1_000_000);}});
                    output.put("visualResponse",result.visual());
                    var facts=VideoFactsValidator.facts(media,result.visual(),result.transcript().rawText(),config.path("model").asText(),result.transcript());
                    output.put("status","SUCCEEDED");output.put("summary",facts.summary());output.put("moments",facts.moments());output.put("transcript",facts.transcript());
                }catch(Exception failure){output.put("status","FAILED");output.put("errorType",failure.getClass().getSimpleName());if(failure instanceof com.tianmu.agent.core.AgentException agent)output.put("errorCode",agent.code());}
                output.put("elapsedMs",(System.nanoTime()-begin)/1_000_000);output.put("audioMs",audioMs.get());output.put("visualMs",visualMs.get());output.put("usage",usage.totals());
                System.out.println(json.writeValueAsString(output));System.out.flush();
            }
        }
    }
}
