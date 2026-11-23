package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.ExecutionPolicySnapshot.Context;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.core.TaskTypes.Specification;
import com.tianmu.agent.model.ModelPageContext;
import com.tianmu.agent.preparation.PreparationModelPort;
import com.tianmu.agent.preparation.TaskMeaning;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.tianmu.agent.model.deepseek.StructuredModelOutput.*;

final class DeepSeekPreparationModel implements PreparationModelPort {
    private record CandidateView(String id,String label,String description,String boundary,String skill,
                                 List<String> requiredConditions,Intent category) {
        CandidateView(IntentCandidate candidate) {
            this(candidate.id(),candidate.label(),candidate.description(),candidate.boundary(),candidate.skill(),
                    candidate.requiredConditions(),candidate.category());
        }
    }
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final String emotionPrompt, understandingPrompt, intentPrompt, legacyIntentPrompt;
    private final PromptCatalog prompts;
    DeepSeekPreparationModel(DeepSeekClient client, DeepSeekProperties config, ObjectMapper mapper, PromptCatalog prompts) {
        this.client = client; this.config = config; this.mapper = mapper;this.prompts=prompts;
        String common = "\n\n" + prompts.text("common.json-output");
        emotionPrompt = prompts.text("preparation.emotion") + common;
        understandingPrompt = prompts.text("preparation.understanding") + common;
        intentPrompt = prompts.text("preparation.intent") + common;
        legacyIntentPrompt = prompts.text("preparation.intent-legacy") + common;
    }
    private Map<String, Object> context(Input input, CallControl control, boolean includeTaskContext) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("memory", Context.usesCompactPreparation(client.harnessProjection(control))
                ? preparationMemory(input, includeTaskContext) : legacyMemory(input));
        payload.put("query", input.query());
        return payload;
    }
    private JsonNode legacyMemory(Input input) {
        JsonNode memory = mapper.valueToTree(input.memory());
        memory.path("recent").forEach(message -> ((ObjectNode) message).remove("at"));
        memory.path("persistent").forEach(item -> ((ObjectNode) item).remove("expiresAt"));
        return memory;
    }
    private ObjectNode preparationMemory(Input input, boolean includeTaskContext) {
        var source = input.memory();
        var memory = mapper.createObjectNode();
        var recent = memory.putArray("recent");
        for (var message : source.recent()) {
            recent.addObject().put("role", message.role()).put("text", message.text());
        }
        if (!includeTaskContext) return memory;
        if (source.olderSummary() != null && !source.olderSummary().isBlank()) memory.put("olderSummary", source.olderSummary());
        if (!source.compaction().isEmpty()) {
            var compaction = memory.putArray("compaction");
            for (var segment : source.compaction()) {
                ObjectNode content = mapper.valueToTree(segment.content());
                content.path("claims").forEach(claim -> {
                    if (claim instanceof ObjectNode fact) fact.remove("source_refs");
                });
                compaction.add(content);
            }
        }
        var focus = source.focus();
        if (focus != null && !focus.request().equals(input.query())) {
            var prior = memory.putObject("focus").put("request", focus.request());
            if (focus.referenceVideoId() != null) prior.put("referenceVideoId", focus.referenceVideoId());
            if (focus.task() != null) prior.set("task", mapper.valueToTree(focus.task()));
        }
        var environment = ModelPageContext.project(source.environment(), input.query());
        if (!environment.isEmpty()) memory.set("environment", mapper.valueToTree(environment));
        return memory;
    }
    private JsonNode call(String id,String variant,String prompt, Map<String, Object> payload, CallControl control, int tokens) {
        return prompts.trace(id,variant,prompt,java.util.List.of("common.json-output"),()->client.complete(config.preparationModel(), client.messages(prompt, payload,control), tokens, control));
    }
    @Override public String revision() {
        return revision(client.defaultHarnessProjection());
    }
    @Override public String revision(String projection) {
        String inputVersion = Context.usesCompactPreparation(projection) ? "preparation-input-v4" : "preparation-input-v3";
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(
                java.util.List.of("chat-native-v1",inputVersion,config.baseUrl().toString(),config.preparationModel(),config.preparationMaxTokens(),emotionPrompt,understandingPrompt,intentPrompt,legacyIntentPrompt))));
        } catch(Exception error){throw new IllegalStateException("Cannot fingerprint preparation model",error);}
    }
    public Emotion emotion(Input input, CallControl control) {
        JsonNode result = call("preparation.emotion","CLASSIFY",emotionPrompt, context(input,control,false), control, 128);
        fields(result, "emotion"); return Emotion.valueOf(text(result, "emotion"));
    }
    public Intent intent(Input input, CallControl control) {
        return recognizeIntent(input,control).intent();
    }
    @Override public IntentRecognition recognizeIntent(Input input,CallControl control) {
        // Explicit legacy adapter inputs retain their old response contract.
        if(input.intentCandidates().isEmpty()) {
            JsonNode result=call("preparation.intent-legacy","LEGACY",legacyIntentPrompt,context(input,control,true),control,128);
            fields(result,"intent");return new IntentRecognition(Intent.valueOf(text(result,"intent")),null,java.util.List.of(),java.util.List.of());
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("intentCandidates", input.intentCandidates().stream().map(CandidateView::new).toList());
        payload.putAll(context(input,control,true));
        JsonNode result=call("preparation.intent","CANDIDATES",intentPrompt,payload,control,256);fields(result,"primary","related");
        if(!result.path("related").isArray() || result.path("related").size()>4)throw new IllegalArgumentException("Invalid related goals");
        var related=new java.util.ArrayList<String>();
        for(var item:result.path("related")){if(!item.isTextual())throw new IllegalArgumentException("Invalid related goal");related.add(item.textValue());}
        return com.tianmu.agent.preparation.IntentCandidateCatalog.validate(input,
                new IntentRecognition(Intent.UNKNOWN,text(result,"primary"),related,input.intentCandidates()));
    }
    public Understanding understand(Input input, CallControl control) {
        Specification candidate;
        try {
            candidate = TaskMeaning.defaults().resolve(input.query(), input.businessDate(), input.requestedAt(), input.memory());
        } catch (AgentException ambiguous) {
            if (!"AMBIGUOUS_ANALYSIS_SOURCES".equals(ambiguous.code())) throw ambiguous;
            return clarification(input, new Specification("unclassified", Map.of(), Map.of()), true, control);
        }
        return clarification(input, candidate, false, control);
    }
    @Override
    public Understanding clarification(Input input, Specification taskCandidate, boolean requiresClarification, CallControl control) {
        var payload = context(input,control,true);
        payload.put("businessDate", input.businessDate().toString());
        ObjectNode candidate = mapper.valueToTree(taskCandidate);
        candidate.put("requiresClarification", requiresClarification);
        payload.put("serverTaskCandidate", candidate);
        JsonNode result = call("preparation.understanding","CLARIFICATION",understandingPrompt, payload, control, config.preparationMaxTokens());
        boolean structured=result.has("issue") || result.has("missingFields");
        if(structured)fields(result,"needsClarification","clarificationQuestion","conditions","rewrittenQuery","issue","missingFields");
        else fields(result, "needsClarification", "clarificationQuestion", "conditions", "rewrittenQuery");
        if (!result.path("needsClarification").isBoolean() || !result.path("conditions").isObject())
            throw new IllegalArgumentException("Invalid understanding structure");
        for (String key : new String[]{"clarificationQuestion", "rewrittenQuery"})
            if (!result.path(key).isNull() && !result.path(key).isTextual())
                throw new IllegalArgumentException("Invalid understanding text");
        Map<String, Object> conditions = mapper.convertValue(result.path("conditions"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        boolean clarify = result.path("needsClarification").booleanValue();
        String rewrite = result.path("rewrittenQuery").textValue();
        if(!structured)return new Understanding(clarify,result.path("clarificationQuestion").textValue(),conditions,rewrite);
        if(!result.path("issue").isTextual() || !result.path("missingFields").isArray())throw new IllegalArgumentException("Invalid clarification issue");
        var missing=new java.util.ArrayList<String>();
        for(var field:result.path("missingFields")){if(!field.isTextual())throw new IllegalArgumentException("Invalid missing field");missing.add(field.textValue());}
        return new Understanding(clarify,result.path("clarificationQuestion").textValue(),conditions,rewrite,ClarificationIssue.valueOf(result.path("issue").asText()),missing);
    }
}
