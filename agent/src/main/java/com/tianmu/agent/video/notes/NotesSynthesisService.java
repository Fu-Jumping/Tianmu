package com.tianmu.agent.video.notes;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.control.CallControl;
import java.util.*;

/** Bounded synthesis steps, deliberately separate from durable dispatch and publication. */
public final class NotesSynthesisService {
    private final NotesModelPort model;
    private final ObjectMapper json;
    private final int maxInputBytes,maxOutputTokens;
    public NotesSynthesisService(NotesModelPort model,ObjectMapper json,int maxInputBytes,int maxOutputTokens) {
        this.model=Objects.requireNonNull(model);this.json=json.copy();
        if(maxInputBytes<1024 || maxInputBytes>1048576 || maxOutputTokens<128 || maxOutputTokens>16384)
            throw new IllegalArgumentException("Invalid notes synthesis limits");
        this.maxInputBytes=maxInputBytes;this.maxOutputTokens=maxOutputTokens;
    }
    public String generationVersion(){return model.version();}
    public String qualityPolicyDigest(){return NotesJob.hash("notes-quality-v1",model.version());}
    public NotesDocument draft(NotesMaterialReader.Prepared material,String videoTitle,CallControl control) {
        if(videoTitle==null || videoTitle.isBlank() || videoTitle.length()>300 || !material.coverage().fullyProcessed()) throw invalid();
        var data=sources(material);data.put("videoTitle",videoTitle);
        JsonNode response=model.request(NotesModelPort.Operation.DRAFT,bounded(data),maxOutputTokens,control);
        return parseDraft(material,response);
    }
    public NotesDocument repair(NotesMaterialReader.Prepared material,NotesDocument previous,NotesDocument.QualityReceipt quality,CallControl control) {
        material.requireMatching(previous);if(quality.passed() || quality.majorIssues().isEmpty())throw invalid();
        var data=sources(material);data.put("previousDraft",Map.of("title",previous.title(),"overview",previous.overview(),"keyPoints",previous.keyPoints(),"sections",previous.sections(),"conclusions",previous.conclusions()));
        data.put("reviewIssues",quality.majorIssues());
        return parseDraft(material,model.request(NotesModelPort.Operation.REPAIR,bounded(data),maxOutputTokens,control));
    }
    private NotesDocument parseDraft(NotesMaterialReader.Prepared material,JsonNode response) {
        try {
            if(response==null || !response.isObject() || !response.path("sections").isArray() || response.path("sections").isEmpty() || response.path("sections").size()>500) throw invalid();
            Map<String,NotesCoverage.Unit> registry=new HashMap<>();material.coverage().units().forEach(u->registry.put(u.id(),u));
            List<NotesDocument.Section> sections=new ArrayList<>();Set<String> used=new HashSet<>();int ordinal=0;
            for(var section:response.path("sections")) {
                List<NotesDocument.Block> blocks=new ArrayList<>();long start=Long.MAX_VALUE,end=0;
                if(!section.path("blocks").isArray() || section.path("blocks").isEmpty() || section.path("blocks").size()>500) throw invalid();
                for(var block:section.path("blocks")) {
                    if(!block.path("citations").isArray() || block.path("citations").isEmpty() || block.path("citations").size()>100) throw invalid();
                    List<NotesDocument.Citation> citations=new ArrayList<>();
                    for(var citation:block.path("citations")) {
                        var unit=registry.get(text(citation,"unitId"));if(unit==null)throw invalid();
                        var modality=NotesDocument.Modality.valueOf(text(citation,"modality"));
                        citations.add(new NotesDocument.Citation(unit.id(),modality,unit.dependency(),modality==NotesDocument.Modality.AUDIO?unit.audioDigest():unit.visualDigest()));
                        used.add(unit.id()); start=Math.min(start,unit.dependency().startMs());end=Math.max(end,unit.dependency().endMs());
                    }
                    blocks.add(new NotesDocument.Block(NotesDocument.Kind.valueOf(text(block,"kind")),text(block,"text"),
                            strings(block.path("items")),rows(block.path("rows")),citations));
                }
                sections.add(new NotesDocument.Section("section-"+(++ordinal),text(section,"title"),new NotesCoverage.Range(start,end),blocks,strings(section.path("uncertainties"))));
            }
            // A full manifest must not hide omitted chunks in the actual document. This is a conservative
            // structural floor, not proof that every important claim survived summarization.
            if(!used.containsAll(registry.keySet())) throw new AgentException("NOTES_CONTENT_OMITTED","Draft omits a processed interval");
            return new NotesDocument(1,text(response,"title"),text(response,"overview"),strings(response.path("keyPoints")),sections,strings(response.path("conclusions")),material.coverage());
        } catch(AgentException failure){throw failure;}
        catch(RuntimeException invalid){throw new AgentException("NOTES_INVALID_DRAFT","Draft does not match the document contract");}
    }
    public NotesDocument.QualityReceipt check(NotesMaterialReader.Prepared material,NotesDocument document,CallControl control) {
        material.requireMatching(document);
        var data=sources(material);data.put("document",document);
        JsonNode response=model.request(NotesModelPort.Operation.CHECK,bounded(data),maxOutputTokens,control);
        return quality(document,response);
    }
    public NotesDocument.QualityReceipt recheck(NotesMaterialReader.Prepared material,NotesDocument document,CallControl control) {
        material.requireMatching(document);var data=sources(material);data.put("document",document);
        return quality(document,model.request(NotesModelPort.Operation.RECHECK,bounded(data),maxOutputTokens,control));
    }
    public NotesDocument.QualityReceipt arbitrate(NotesMaterialReader.Prepared material,NotesDocument document,NotesDocument.QualityReceipt prior,CallControl control) {
        material.requireMatching(document);if(prior.passed() || prior.majorIssues().isEmpty())throw invalid();
        var data=sources(material);data.put("document",document);data.put("priorReviewIssues",prior.majorIssues());
        return quality(document,model.request(NotesModelPort.Operation.ARBITRATE,bounded(data),maxOutputTokens,control));
    }
    private NotesDocument.QualityReceipt quality(NotesDocument document,JsonNode response) {
        if(response==null || !response.path("passed").isBoolean()) throw invalid();
        JsonNode reported=response.path("majorIssues");
        if(!reported.isArray() || reported.size()>100)throw invalid();
        List<String> issues=new ArrayList<>();
        for(var issue:reported) {
            // Some compatible models return a structured defect instead of a string. Keep the
            // complete evidence for repair; never drop a reported defect to obtain a quality pass.
            String message;
            if(issue.isTextual())message=issue.textValue();
            else if(issue.isObject() && issue.path("error").isTextual() && !issue.path("error").textValue().isBlank())message=issue.toString();
            else throw invalid();
            if(message.isBlank() || message.length()>2000)throw invalid();
            issues.add(message);
        }
        return new NotesDocument.QualityReceipt(document.contentDigest(),qualityPolicyDigest(),response.path("passed").booleanValue() && issues.isEmpty(),issues);
    }
    private Map<String,Object> sources(NotesMaterialReader.Prepared material) {
        if(!material.coverage().fullyProcessed()) throw invalid();
        Map<String,Object> data=new LinkedHashMap<>();data.put("coverage",material.coverage());data.put("sources",material.sources());return data;
    }
    private Map<String,Object> bounded(Map<String,Object> data) {
        try {if(json.writeValueAsBytes(data).length>maxInputBytes) throw new AgentException("NOTES_SYNTHESIS_LIMIT","Synthesis input too large; requires bounded chapter preparation");}
        catch(java.io.IOException invalid){throw invalid();}
        return data;
    }
    private static String text(JsonNode node,String field){if(!node.path(field).isTextual())throw invalid();return node.path(field).textValue();}
    private static List<String> strings(JsonNode node) {
        if(!node.isArray() || node.size()>500)throw invalid();List<String> result=new ArrayList<>();
        for(var item:node){if(!item.isTextual())throw invalid();result.add(item.textValue());}return result;
    }
    private static List<List<String>> rows(JsonNode node) {
        if(!node.isArray() || node.size()>200)throw invalid();List<List<String>> result=new ArrayList<>();for(var row:node)result.add(strings(row));return result;
    }
    private static AgentException invalid(){return new AgentException("NOTES_INVALID_DRAFT","Invalid synthesis response");}
}
