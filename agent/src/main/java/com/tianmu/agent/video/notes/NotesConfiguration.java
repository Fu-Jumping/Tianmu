package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.business.BusinessProperties;
import com.tianmu.agent.model.ContextPolicy;
import com.tianmu.agent.model.deepseek.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.store.ObjectStore;
import com.tianmu.agent.video.*;
import com.tianmu.agent.video.content.*;
import io.opentelemetry.api.trace.Tracer;
import java.nio.file.*;
import java.time.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Opt-in assembly. No notes endpoint or paid background calls exist until explicitly enabled. */
@Configuration(proxyBeanMethods=false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(NotesVisionProperties.class)
@ConditionalOnProperty(name="agent.video.notes.enabled",havingValue="true")
public class NotesConfiguration {
    private static final String ANALYSIS="notes-source-v3";
    @Bean MysqlNotesJobStore notesJobs(DataSource db,ObjectMapper json,Flyway migrations){return new MysqlNotesJobStore(db,json);}
    @Bean MysqlContentWorkStore notesContentJobs(DataSource db,ObjectMapper json,Flyway migrations){return new MysqlContentWorkStore(db,json);}
    @Bean HttpContentProcessingAuthority notesAuthority(BusinessProperties business,ObjectMapper json,
            @Value("${agent.video.notes.processing-token-file}") String file)throws Exception {
        var path=Path.of(file);if(Files.size(path)>4096)throw new IllegalArgumentException("Invalid processing token file");
        return new HttpContentProcessingAuthority(business.baseUrl(),Files.readString(path).strip(),Duration.ofSeconds(60),json);
    }
    @Bean NotesProcessingGrants notesGrants(HttpContentProcessingAuthority authority){return new NotesProcessingGrants(authority);}
    @Bean ContentWorkService notesWork(MysqlContentWorkStore jobs,HttpContentProcessingAuthority authority){return new ContentWorkService(jobs,authority,Duration.ofSeconds(10));}
    @Bean NotesSourceStore notesSources(ObjectMapper json,@Value("${agent.video.notes.source-directory}") String directory)throws Exception{return new FileNotesSourceStore(Path.of(directory),json);}
    @Bean NotesSynthesisService notesSynthesis(DeepSeekClient client,ObjectMapper json,ContextPolicy context,PromptCatalog prompts,DeepSeekProperties model){
        var adapter=new DeepSeekNotesModel(client,json,context,prompts,model.analysisModel(),model.analysisProtocolRevision());
        return new NotesSynthesisService(adapter,json,Math.min(1048576,context.maxInputBytes()-1024),8192);
    }
    @Bean NotesAdmissionService notesAdmission(ContentReadAuthority authority,MysqlNotesJobStore jobs,NotesSynthesisService synthesis){
        var policy=new NotesJob.Policy("zh-CN","notes-v1",ANALYSIS,synthesis.generationVersion(),30000,1000,100000000L);
        return new NotesAdmissionService(authority,jobs,policy,3600000,Duration.ofHours(12),Clock.systemUTC());
    }
    @Bean NotesDocumentObjects notesDocuments(ObjectStore objects,ObjectMapper json){return new NotesDocumentObjects(objects,json);}
    @Bean NotesSynthesisObjects notesCheckpoints(ObjectStore objects,ObjectMapper json){return new NotesSynthesisObjects(objects,json);}
    @Bean NotesPublicationService notesPublication(MysqlNotesJobStore jobs,NotesDocumentObjects objects,NotesProcessingGrants grants,NotesAdmissionService admission,NotesSynthesisService synthesis){
        return new NotesPublicationService(jobs,objects,grants,admission,synthesis.qualityPolicyDigest());
    }
    @Bean NotesMaterialReader notesMaterials(MysqlNotesJobStore jobs,MysqlContentWorkStore content,NotesSourceStore sources,NotesProcessingGrants grants){return new NotesMaterialReader(jobs,content,sources,grants,1000,2000000);}
    @Bean NotesSourceProcessor notesSourceProcessor(HttpContentProcessingAuthority authority,TranscriptionPort asr,VisionModelPort vision,
            NotesVisionProperties selected,ObjectMapper json,VideoRuntimeProperties runtime)throws Exception {
        if(selected.configured()) {
            Path key=Path.of(selected.keyFile());if(Files.size(key)>4096)throw new IllegalArgumentException("Invalid notes vision key file");
            var properties=new VideoModelProperties(true,"compatible",java.net.URI.create(selected.baseUrl()),Files.readString(key).strip(),selected.model(),"");
            var adapter=new VideoModelClient(properties,json,runtime);
            vision=(media,focus,speech,control)->{
                io.opentelemetry.api.trace.Span.current().setAttribute("notes.vision_model",selected.model());
                io.opentelemetry.api.trace.Span.current().setAttribute("notes.vision_provider",java.net.URI.create(selected.baseUrl()).getHost());
                return adapter.understand(media,focus,speech,control);
            };
        }
        return new NotesSourceProcessor(authority,asr,vision);
    }
    @Bean(destroyMethod="") NotesSourceWorker notesSourceWorker(MysqlNotesJobStore jobs,MysqlContentWorkStore content,ContentWorkService work,
            NotesSourceProcessor processor,NotesSourceStore sources,NotesProcessingGrants grants){
        return new NotesSourceWorker(jobs,content,work,processor,sources,grants,"notes-source",ANALYSIS,Duration.ofSeconds(60),Duration.ofSeconds(15),100000L);
    }
    @Bean(destroyMethod="") NotesSynthesisWorker notesSynthesisWorker(MysqlNotesJobStore jobs,MysqlContentWorkStore content,ContentWorkService work,NotesMaterialReader materials,
            NotesSynthesisService synthesis,NotesSynthesisObjects checkpoints,NotesDocumentObjects documents,NotesPublicationService publication,NotesProcessingGrants grants){
        return new NotesSynthesisWorker(jobs,content,work,materials,synthesis,checkpoints,documents,publication,grants,"notes-summary",Duration.ofSeconds(60),Duration.ofSeconds(15),100000L);
    }
    @Bean NotesCoordinator notesCoordinator(MysqlNotesJobStore jobs,MysqlContentWorkStore content,ContentWorkService work,NotesProcessingGrants grants){return new NotesCoordinator(jobs,content,work,grants,Clock.systemUTC(),Duration.ofSeconds(600));}
    @Bean NotesRetryService notesRetry(NotesAdmissionService admission,MysqlNotesJobStore jobs,MysqlContentWorkStore content,ContentWorkService work,NotesProcessingGrants grants){return new NotesRetryService(admission,jobs,content,work,grants);}
    @Bean NotesSynthesisCoordinator notesSynthesisCoordinator(MysqlNotesJobStore jobs,MysqlContentWorkStore content,ContentWorkService work,NotesProcessingGrants grants,NotesSynthesisWorker worker){return new NotesSynthesisCoordinator(jobs,content,work,grants,worker,Duration.ofSeconds(600));}
    @Bean(initMethod="start",destroyMethod="close") NotesWorkerScheduler notesWorkerScheduler(MysqlNotesJobStore jobs,NotesCoordinator coordinator,NotesSynthesisCoordinator synthesis,NotesSourceWorker sources,NotesSynthesisWorker summaries,Tracer tracer){return new NotesWorkerScheduler(jobs,coordinator,synthesis,sources,summaries,tracer);}
}
