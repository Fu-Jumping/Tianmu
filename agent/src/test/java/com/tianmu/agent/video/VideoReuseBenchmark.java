package com.tianmu.agent.video;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.BailianTranscriptionClient;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.nio.file.*;
import java.net.URI;
import java.time.*;
import java.util.*;
/** Shadow fixture benchmark: source authorization and final answer generation are not under test. */
public final class VideoReuseBenchmark {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var cfg=json.readTree(Files.readString(Path.of(args[0])));
        var input=json.readTree(Files.readString(Path.of(args[1])));var media=new HashMap<String,VideoContent.Media>();
        for(var x:input){var m=json.treeToValue(x.get("media"),VideoContent.Media.class);media.put(x.path("id").asText(),m);}
        var requests=new ArrayList<List<Long>>();var old=media.get("old");var full=media.get("full");
        var port=new VideoContentPort(){
            public VideoContent.Source source(long id,AccessScope s,CallControl c){return old.source();}
            public VideoContent.Media media(long id,String version,long from,long to,AccessScope s,CallControl c){
                requests.add(List.of(from,to));return media.values().stream().filter(m->m.startMs()==from && m.endMs()==to && m.source().sourceVersion().equals(version)).findFirst().orElseThrow();
            }
        };
        var beans=new StaticListableBeanFactory();beans.addBean("fixture",port);
        var config=new VideoModelProperties(true,cfg.path("provider").asText(),URI.create(cfg.path("baseUrl").asText()),Files.readString(Path.of(cfg.path("visionKeyFile").asText())).strip(),cfg.path("model").asText(),"");
        var vision=new VideoModelClient(config,json);var audio=BailianTranscriptionClient.create(cfg.path("region").asText(),Files.readString(Path.of(cfg.path("asrKeyFile").asText())).strip(),json,Duration.ofSeconds(60));
        var scope=new AccessScope("shadow","same-viewer",Set.of(old.source().videoId()));
        var reused=new VideoAnalysisService(config,beans.getBeanProvider(VideoContentPort.class),new VideoContentCache(beans.getBeanProvider(javax.sql.DataSource.class),json),OpenTelemetry.noop().getTracer("shadow"),vision,audio,new VideoAnalysisFlights());
        var field=VideoAnalysisService.class.getDeclaredField("reuse");field.setAccessible(true);field.set(reused,new VideoReusePolicy(true));
        reused.analyze(old.source().videoId(),old.startMs(),old.endMs(),"",scope,control());requests.clear();
        var baseline=new VideoAnalysisService(config,beans.getBeanProvider(VideoContentPort.class),new VideoContentCache(beans.getBeanProvider(javax.sql.DataSource.class),json),OpenTelemetry.noop().getTracer("shadow"),vision,audio,new VideoAnalysisFlights());
        for(boolean reuse:new boolean[]{false,true}){
            requests.clear();long start=System.nanoTime();var output=new LinkedHashMap<String,Object>();output.put("reuse",reuse);
            try{
                var result=reuse?reused.analyzeCurrent(full.source().videoId(),full.startMs(),full.endMs(),full.endMs()-90000,"",scope,control()):List.of(baseline.analyze(full.source().videoId(),full.startMs(),full.endMs(),"",scope,control()));
                output.put("status","SUCCEEDED");output.put("parts",result);
            }catch(Exception error){output.put("status","FAILED");output.put("errorType",error.getClass().getSimpleName());if(error instanceof com.tianmu.agent.core.AgentException e)output.put("errorCode",e.code());}
            output.put("elapsedMs",(System.nanoTime()-start)/1_000_000);output.put("extractedRanges",List.copyOf(requests));System.out.println(json.writeValueAsString(output));System.out.flush();
        }
    }
    private static CallControl control(){return new CallControl(Instant.now().plusSeconds(100),new CancellationToken());}
}
