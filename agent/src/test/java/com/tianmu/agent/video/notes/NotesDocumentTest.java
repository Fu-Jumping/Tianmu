package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.video.content.ContentAssetKey;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.tianmu.agent.video.notes.NotesCoverage.*;
import static com.tianmu.agent.video.notes.NotesDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class NotesDocumentTest {
    final String digest = "a".repeat(64), other = "b".repeat(64);
    final ContentAssetKey asset = new ContentAssetKey("tenant", 1, "version", "public", 0);
    Unit unit(String id, long start, long end, Audio audio, Visual visual) {
        return new Unit(id, asset, new Range(start,end), new Range(start,end), audio, visual, digest, digest,
                visual == Visual.SAMPLED ? List.of(start) : List.of());
    }
    NotesCoverage coverage(List<Unit> units) { return new NotesCoverage(asset,1000,digest,units); }
    NotesDocument document(NotesCoverage coverage, Citation citation, String text) {
        return new NotesDocument(1,"视频标题","完整主题概览",List.of("重点"),List.of(
                new Section("section-1","定义",new Range(0,1000),List.of(
                        new Block(Kind.PARAGRAPH,text,List.of(),List.of(),List.of(citation))),List.of())),List.of(),coverage);
    }
    Citation citation() { return new Citation("first",Modality.AUDIO,new Range(0,1000),digest); }
    @Test void fullCoverageIsNecessaryButNotEnoughForPublication() {
        var doc = document(coverage(List.of(unit("first",0,1000,Audio.TRANSCRIBED,Visual.SAMPLED))),citation(),"映射定义");
        assertTrue(doc.coverage().fullyProcessed());
        assertThrows(IllegalArgumentException.class, () -> doc.requirePublishable(new QualityReceipt(doc.contentDigest(),digest,false,List.of("公式不清楚")),digest));
        assertDoesNotThrow(() -> doc.requirePublishable(new QualityReceipt(doc.contentDigest(),digest,true,List.of()),digest));
    }
    @Test void missingTailGapAndModalityFailuresNeverCountAsComplete() {
        assertFalse(coverage(List.of(unit("first",0,900,Audio.TRANSCRIBED,Visual.SAMPLED))).fullyProcessed());
        assertFalse(coverage(List.of(unit("a",0,400,Audio.TRANSCRIBED,Visual.SAMPLED),unit("b",500,1000,Audio.TRANSCRIBED,Visual.SAMPLED))).fullyProcessed());
        assertFalse(coverage(List.of(unit("first",0,1000,Audio.FAILED,Visual.SAMPLED))).fullyProcessed());
    }
    @Test void noSpeechIsDistinctFromFailureAndCannotBeCitedAsTranscript() {
        var manifest = coverage(List.of(unit("first",0,1000,Audio.NO_SPEECH,Visual.SAMPLED)));
        assertTrue(manifest.fullyProcessed());
        assertThrows(IllegalArgumentException.class, () -> document(manifest,citation(),"老师说"));
        assertDoesNotThrow(() -> document(manifest,new Citation("first",Modality.VISUAL,new Range(0,1000),digest),"画面内容"));
    }
    @Test void noTrackRequiresProofAndVideoStillNeedsOneModality() {
        assertThrows(IllegalArgumentException.class, () -> new Unit("x",asset,new Range(0,1000),new Range(0,1000),Audio.NO_AUDIO_TRACK,Visual.SAMPLED,"",digest,List.of(0L)));
        assertThrows(IllegalArgumentException.class, () -> unit("x",0,1000,Audio.NO_AUDIO_TRACK,Visual.NO_VISUAL_TRACK));
        assertTrue(coverage(List.of(unit("first",0,1000,Audio.TRANSCRIBED,Visual.NO_VISUAL_TRACK))).fullyProcessed());
    }
    @Test void rejectsMixedIdentityOverlapsAndDuplicateEvidence() {
        assertThrows(IllegalArgumentException.class, () -> coverage(List.of(unit("x",0,700,Audio.TRANSCRIBED,Visual.SAMPLED),unit("y",600,1000,Audio.TRANSCRIBED,Visual.SAMPLED))));
        assertThrows(IllegalArgumentException.class, () -> coverage(List.of(unit("x",0,500,Audio.TRANSCRIBED,Visual.SAMPLED),unit("x",500,1000,Audio.TRANSCRIBED,Visual.SAMPLED))));
        var foreign = new ContentAssetKey("other",1,"version","public",0);
        assertThrows(IllegalArgumentException.class, () -> coverage(List.of(new Unit("x",foreign,new Range(0,1000),new Range(0,1000),Audio.TRANSCRIBED,Visual.SAMPLED,digest,digest,List.of(0L)))));
    }
    @Test void frameAtExclusiveEndOrOnlyInOverlapDoesNotCoverOwnedRange() {
        assertThrows(IllegalArgumentException.class, () -> new Unit("x",asset,new Range(500,1000),new Range(0,1000),Audio.TRANSCRIBED,Visual.SAMPLED,digest,digest,List.of(1000L)));
        assertThrows(IllegalArgumentException.class, () -> new Unit("x",asset,new Range(500,1000),new Range(0,1000),Audio.TRANSCRIBED,Visual.SAMPLED,digest,digest,List.of(0L)));
    }
    @Test void rejectsFabricatedCitationsAndDigestSubstitution() {
        var manifest = coverage(List.of(unit("first",0,1000,Audio.TRANSCRIBED,Visual.SAMPLED)));
        for (Citation citation : List.of(new Citation("missing",Modality.AUDIO,new Range(0,1000),digest),
                new Citation("first",Modality.AUDIO,new Range(0,1001),digest),new Citation("first",Modality.AUDIO,new Range(0,1000),other)))
            assertThrows(IllegalArgumentException.class, () -> document(manifest,citation,"内容"));
    }
    @Test void changedTextOrQualityPolicyInvalidatesReceipt() {
        var manifest = coverage(List.of(unit("first",0,1000,Audio.TRANSCRIBED,Visual.SAMPLED)));
        var original=document(manifest,citation(),"内容A"); var edited=document(manifest,citation(),"内容B");
        var receipt = new QualityReceipt(original.contentDigest(),digest,true,List.of());
        assertThrows(IllegalArgumentException.class, () -> edited.requirePublishable(receipt,digest));
        assertThrows(IllegalArgumentException.class, () -> original.requirePublishable(receipt,other));
    }
    @Test void jsonRoundTripKeepsDigestAndPlainCopyUsesNoModel() throws Exception {
        var original=document(coverage(List.of(unit("first",0,1000,Audio.TRANSCRIBED,Visual.SAMPLED))),citation(),"x < y；f:X→Y");
        var mapper = new ObjectMapper(); var restored=mapper.readValue(mapper.writeValueAsBytes(original),NotesDocument.class);
        assertEquals(original,restored); assertEquals(original.contentDigest(),restored.contentDigest());
        assertTrue(restored.plainText().contains("x < y")); assertTrue(restored.plainText().contains("0:00–0:01"));
        assertTrue(restored.plainText().contains("画面基于采样分析"));
    }
    @Test void rejectsJaggedTablesAndUncitedBlocks() {
        assertThrows(IllegalArgumentException.class, () -> new Block(Kind.TABLE,"",List.of(),List.of(List.of("A","B"),List.of("C")),List.of(citation())));
        assertThrows(IllegalArgumentException.class, () -> new Block(Kind.PARAGRAPH,"内容",List.of(),List.of(),List.of()));
    }
    @Test void cannotInventPreciseAudioOrVisualEvidenceBetweenFrames() {
        var manifest=coverage(List.of(unit("first",0,1000,Audio.TRANSCRIBED,Visual.SAMPLED)));
        assertThrows(IllegalArgumentException.class,()->document(manifest,new Citation("first",Modality.AUDIO,new Range(500,501),digest),"精确原话"));
        assertThrows(IllegalArgumentException.class,()->document(manifest,new Citation("first",Modality.VISUAL,new Range(500,501),digest),"动作"));
    }
    @Test void failedEvidenceHasCanonicalEmptyDigestForPartialDocument() {
        var failed = new Unit("first",asset,new Range(0,1000),new Range(0,1000),Audio.FAILED,Visual.SAMPLED,null,digest,List.of(0L));
        var doc=document(coverage(List.of(failed)),new Citation("first",Modality.VISUAL,new Range(0,1000),digest),"画面内容");
        // A failed audio unit cannot currently supply publication evidence, even for the visual block.
        assertFalse(doc.coverage().fullyProcessed());
        assertNotNull(doc.contentDigest());
    }
}
