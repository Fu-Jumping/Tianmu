package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.content.ContentAssetKey;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotesSynthesisServiceTest {
    final ObjectMapper json=new ObjectMapper(); final NotesModelPort model=mock(NotesModelPort.class);
    final String digest="a".repeat(64);
    final ContentAssetKey asset=new ContentAssetKey("tenant",1,"version","public",0);
    final NotesCoverage coverage=new NotesCoverage(asset,1000,digest,List.of(new NotesCoverage.Unit("clip",asset,new NotesCoverage.Range(0,1000),new NotesCoverage.Range(0,1000),NotesCoverage.Audio.TRANSCRIBED,NotesCoverage.Visual.SAMPLED,digest,digest,List.of(0L))));
    final NotesMaterialReader.Prepared material=new NotesMaterialReader.Prepared(UUID.randomUUID(),1,1,digest,coverage,List.of());
    CallControl control(){return new CallControl(Instant.now().plusSeconds(60),new CancellationToken());}
    NotesSynthesisService service(){when(model.version()).thenReturn("model-policy-1");return new NotesSynthesisService(model,json,65536,4096);}
    JsonNode draft() throws Exception {return json.readTree("""
        {"title":"映射","overview":"映射定义","keyPoints":["每个输入有唯一输出"],"sections":[{"title":"定义","blocks":[{"kind":"PARAGRAPH","text":"不同输入可以对应同一输出。","items":[],"rows":[],"citations":[{"unitId":"clip","modality":"AUDIO","range":{"startMs":999,"endMs":1000},"sourceDigest":"伪造摘要"}]}],"uncertainties":[]}],"conclusions":[]}
        """);}
    @Test void hostRebuildsTimesDigestsAndMediaRatherThanTrustingModel() throws Exception {
        when(model.request(eq(NotesModelPort.Operation.DRAFT),anyMap(),anyInt(),any())).thenReturn(draft());
        var document=service().draft(material,"原视频标题",control());var citation=document.sections().get(0).blocks().get(0).citations().get(0);
        assertEquals(new NotesCoverage.Range(0,1000),citation.range());assertEquals(digest,citation.sourceDigest());assertEquals(coverage,document.coverage());
    }
    @Test void fabricatedEvidenceIdFailsClosed() throws Exception {
        var value=draft();((com.fasterxml.jackson.databind.node.ObjectNode)value.at("/sections/0/blocks/0/citations/0")).put("unitId","missing");
        when(model.request(any(),anyMap(),anyInt(),any())).thenReturn(value);
        assertThrows(AgentException.class,()->service().draft(material,"标题",control()));
    }
    @Test void omissionOfAProcessedIntervalCannotBecomeACompleteDraft() throws Exception {
        var expanded=new NotesCoverage(asset,2000,digest,List.of(coverage.units().get(0),new NotesCoverage.Unit("tail",asset,new NotesCoverage.Range(1000,2000),new NotesCoverage.Range(1000,2000),NotesCoverage.Audio.TRANSCRIBED,NotesCoverage.Visual.SAMPLED,digest,digest,List.of(1000L))));
        var full=new NotesMaterialReader.Prepared(material.rootId(),1,1,digest,expanded,List.of());when(model.request(any(),anyMap(),anyInt(),any())).thenReturn(draft());
        assertEquals("NOTES_CONTENT_OMITTED",assertThrows(AgentException.class,()->service().draft(full,"标题",control())).code());
    }
    @Test void independentCheckBindsTheActualDraftAndFrozenModelPolicy() throws Exception {
        when(model.request(eq(NotesModelPort.Operation.DRAFT),anyMap(),anyInt(),any())).thenReturn(draft());
        when(model.request(eq(NotesModelPort.Operation.CHECK),anyMap(),anyInt(),any())).thenReturn(json.readTree("{\"passed\":true,\"majorIssues\":[]}"));
        var service=service();var document=service.draft(material,"标题",control());var receipt=service.check(material,document,control());
        assertEquals(document.contentDigest(),receipt.documentDigest());assertEquals(service.qualityPolicyDigest(),receipt.policyDigest());assertTrue(receipt.passed());
    }
    @Test void contradictoryOrMalformedQualityOutputIsNotAQualityPass() throws Exception {
        when(model.request(eq(NotesModelPort.Operation.DRAFT),anyMap(),anyInt(),any())).thenReturn(draft());
        var service=service();var document=service.draft(material,"标题",control());
        when(model.request(eq(NotesModelPort.Operation.CHECK),anyMap(),anyInt(),any())).thenReturn(json.readTree("{\"passed\":true,\"majorIssues\":[\"公式错误\"]}"));
        assertFalse(service.check(material,document,control()).passed());
        when(model.request(eq(NotesModelPort.Operation.CHECK),anyMap(),anyInt(),any())).thenReturn(json.readTree("{\"passed\":\"true\",\"majorIssues\":[]}"));
        assertThrows(AgentException.class,()->service.check(material,document,control()));
    }
    @Test void inputOverBudgetDoesNotCallTheModelOrTruncate() {
        var limited=new NotesSynthesisService(model,json,1024,4096);
        assertThrows(AgentException.class,()->limited.draft(material,"课".repeat(300),control()));verifyNoInteractions(model);
    }
    @Test void structuredProviderDefectsRemainBlockingAndRetainTheirEvidence() throws Exception {
        when(model.request(eq(NotesModelPort.Operation.DRAFT),anyMap(),anyInt(),any())).thenReturn(draft());
        var service=service();var document=service.draft(material,"标题",control());
        when(model.request(eq(NotesModelPort.Operation.CHECK),anyMap(),anyInt(),any())).thenReturn(json.readTree("""
            {"passed":true,"majorIssues":[{"section":"定义","error":"把唯一输出写成允许多个输出","evidence":[{"unitId":"clip","quote":"唯一元素"}],"fix":"恢复唯一性条件"}]}
            """));
        var receipt=service.check(material,document,control());
        assertFalse(receipt.passed());assertEquals(1,receipt.majorIssues().size());
        assertEquals("唯一元素",json.readTree(receipt.majorIssues().get(0)).at("/evidence/0/quote").textValue());
        when(model.request(eq(NotesModelPort.Operation.CHECK),anyMap(),anyInt(),any())).thenReturn(json.readTree("{\"passed\":true,\"majorIssues\":[{}]}"));
        assertThrows(AgentException.class,()->service.check(material,document,control()));
    }
}
