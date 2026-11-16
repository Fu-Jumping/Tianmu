package com.tianmu.agent.video;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.ToolResult;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MaterialEvidenceContractTest {
    @Test void answeringFromOnePartCannotDropTheOtherSource(){
        var both=new ToolResult("video",Map.of(),"first",null,null,null,List.of("second"));
        var exchanges=List.of(new com.tianmu.agent.core.AgentTypes.ToolExchange(new com.tianmu.agent.core.AgentTypes.ToolCall("video",Map.of()),both));
        assertThrows(com.tianmu.agent.core.AgentException.class,()->com.tianmu.agent.application.EvidenceCompletionRequirement.requireGroupedReferences(new com.tianmu.agent.core.AgentTypes.Answer("解释",List.of("first")),exchanges));
        assertDoesNotThrow(()->com.tianmu.agent.application.EvidenceCompletionRequirement.requireGroupedReferences(new com.tianmu.agent.core.AgentTypes.Answer("解释",List.of("first","second")),exchanges));
    }
    @Test void oldResultsRemainReadableAndBothSourcesSurviveHistory() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();
        var old=json.readValue("{\"tool\":\"video\",\"data\":{},\"evidenceId\":\"first\"}",ToolResult.class);
        assertEquals(List.of("first"),old.allEvidenceIds());
        assertFalse(json.writeValueAsString(old).contains("additionalEvidenceIds"));
        var both=new ToolResult("video",Map.of(),"first",null,null,null,List.of("second"));
        assertEquals(both,json.readValue(json.writeValueAsBytes(both),ToolResult.class));
        assertEquals(List.of("first","second"),both.withData(Map.of("x",1)).allEvidenceIds());
        assertThrows(IllegalArgumentException.class,()->new ToolResult("video",Map.of(),null,null,null,null,List.of("second")));
    }
}
