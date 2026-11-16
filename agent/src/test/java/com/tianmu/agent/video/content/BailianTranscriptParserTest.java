package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BailianTranscriptParserTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void mapsProviderRelativeMillisecondsToPlaybackTimeline() throws Exception {
        var response=json.readTree("""
            {"output":{"output":{"sentence":{"sentence_end":true,"begin_time":200,"end_time":30000,"text":"定义域","words":[{"begin_time":200,"end_time":300,"text":"定义"},{"begin_time":300,"end_time":30000,"text":"域"}]}}}}
            """);
        var result=BailianTranscriptParser.parse(response,270000,300000);
        assertEquals(270200,result.segments().get(0).startMs());assertEquals(300000,result.segments().get(0).endMs());
        assertEquals("定义域",result.rawText());assertEquals(TimedTranscript.Precision.SEGMENT,result.precision());
    }
    @Test void rejectsFutureOverlappingAndIncompleteResults() throws Exception {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("""
            {"output":{"sentence":{"sentence_end":true,"begin_time":0,"end_time":1000,"text":"甲乙","words":[{"begin_time":0,"end_time":500,"text":"甲"},{"begin_time":500,"end_time":1000,"text":"乙"}]}}}
            """);
        var sentence=(com.fasterxml.jackson.databind.node.ObjectNode)body.path("output").path("sentence");
        sentence.put("end_time",1001);assertThrows(IllegalArgumentException.class,()->BailianTranscriptParser.parse(body,0,1000));
        sentence.put("end_time",1000);((com.fasterxml.jackson.databind.node.ObjectNode)sentence.path("words").get(1)).put("begin_time",499);
        assertThrows(IllegalArgumentException.class,()->BailianTranscriptParser.parse(body,0,1000));
        sentence.put("sentence_end",false);assertThrows(IllegalArgumentException.class,()->BailianTranscriptParser.parse(body,0,1000));
    }
    @Test void parsesLocalProviderFixturesWhenSupplied() throws Exception {
        String fixtureDir=System.getProperty("bailian.fixtureDir");
        if(fixtureDir==null)return;
        for(String name:new String[]{"mapping","floor"}) {
            var response=json.readTree(java.nio.file.Path.of(fixtureDir,name+"-result.json").toFile()).path("response");
            var result=BailianTranscriptParser.parse(response,270000,300000);
            assertEquals(TimedTranscript.State.AVAILABLE,result.state());
            assertEquals(300000,result.segments().get(0).endMs());
        }
    }
}
