package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.*;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoMaterialReuseTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AtomicReference<String> version=new AtomicReference<>("a".repeat(64));
    final VideoContentPort port=mock(VideoContentPort.class);
    final VideoAnalysisService service;
    VideoMaterialReuseTest(){
        var beans=new StaticListableBeanFactory();beans.addBean("source",port);
        when(port.source(anyLong(),any(),any())).thenAnswer(i->new VideoContent.Source(i.getArgument(0),version.get(),600000));
        when(port.media(anyLong(),anyString(),anyLong(),anyLong(),any(),any())).thenAnswer(i->new VideoContent.Media(new VideoContent.Source(i.getArgument(0),i.getArgument(1),600000),i.getArgument(2),i.getArgument(3),List.of(new VideoContent.Frame((long)i.getArgument(2)+100,"frame")),""));
        var vision=mock(VisionModelPort.class);
        when(vision.understand(any(),anyString(),anyString(),any())).thenReturn(json.createObjectNode().put("summary","板书").set("moments",json.createArrayNode()));
        service=new VideoAnalysisService(new VideoModelProperties(true,"compatible",URI.create("https://vision.example"),"key","vision",""),beans.getBeanProvider(VideoContentPort.class),new VideoContentCache(beans.getBeanProvider(javax.sql.DataSource.class),json),OpenTelemetry.noop().getTracer("test"),vision,mock(TranscriptionPort.class),new VideoAnalysisFlights());
        ReflectionTestUtils.setField(service,"reuse",new VideoReusePolicy(true));
    }
    AccessScope viewer(String id){return new AccessScope("t",id,Set.of(7L));}
    CallControl control(){return new CallControl(Instant.now().plusSeconds(20),new CancellationToken());}
    void warm(){service.analyze(7,270000L,300000L,"",viewer("u"),control());}
    @Test void movingFiveSecondsOnlyProcessesSixSecondsAndKeepsOriginalSources(){
        warm();var parts=service.analyzeCurrent(7,275000,305000,215000,"",viewer("u"),control());
        assertEquals(2,parts.size());assertEquals(270000,parts.get(0).startMs());assertEquals(300000,parts.get(0).endMs());
        assertEquals(299000,parts.get(1).startMs());assertEquals(305000,parts.get(1).endMs());
        verify(port,times(1)).media(eq(7L),anyString(),eq(270000L),eq(300000L),any(),any());
        verify(port,times(1)).media(eq(7L),anyString(),eq(299000L),eq(305000L),any(),any());
        verify(port,never()).media(eq(7L),anyString(),eq(275000L),eq(305000L),any(),any());
    }
    @Test void anotherViewerAndAnotherSourceVersionCannotReuse(){
        warm();assertEquals(1,service.analyzeCurrent(7,275000,305000,215000,"",viewer("other"),control()).size());
        version.set("b".repeat(64));assertEquals(1,service.analyzeCurrent(7,275000,305000,215000,"",viewer("u"),control()).size());
        verify(port,times(2)).media(eq(7L),anyString(),eq(275000L),eq(305000L),any(),any());
    }
    @Test void futureDependencyAndEarlierThanAllowedMaterialCannotBeReused(){
        warm();assertEquals(1,service.analyzeCurrent(7,265000,295000,205000,"",viewer("u"),control()).size());
        assertEquals(1,service.analyzeCurrent(7,275000,305000,275000,"",viewer("u"),control()).size());
    }
    @Test void noReadGrantStopsBeforeSourceOrProvider(){
        var e=assertThrows(AgentException.class,()->service.analyzeCurrent(7,275000,305000,215000,"",new AccessScope("t","u",Set.of()),control()));
        assertEquals("FORBIDDEN",e.code());verifyNoInteractions(port);
    }
    @Test void sourceReplacementDuringTailProcessingRejectsTheWholeBundle(){
        warm();
        when(port.media(eq(7L),anyString(),eq(299000L),eq(305000L),any(),any())).thenAnswer(i->{
            var media=new VideoContent.Media(new VideoContent.Source(7,version.get(),600000),299000,305000,List.of(new VideoContent.Frame(299100,"frame")),"");
            version.set("b".repeat(64));return media;
        });
        assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->service.analyzeCurrent(7,275000,305000,215000,"",viewer("u"),control())).code());
    }
}
