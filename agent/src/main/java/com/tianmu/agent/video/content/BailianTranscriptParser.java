package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Parses the observed Qwen Audio ASR synchronous response. Times are relative milliseconds. */
public final class BailianTranscriptParser {
    private BailianTranscriptParser() {}
    public static TimedTranscript parse(JsonNode response,long clipStartMs,long clipEndMs) {
        if(response==null || !response.isObject() || clipStartMs<0 || clipEndMs<=clipStartMs)
            throw new IllegalArgumentException("Invalid ASR response or clip");
        JsonNode output=response.path("output");
        if(output.path("output").isObject()) output=output.path("output");
        JsonNode sentence=output.path("sentence");
        if(!sentence.isObject() || !sentence.path("text").isTextual() || !sentence.path("sentence_end").asBoolean(false))
            throw new IllegalArgumentException("Missing final ASR sentence");
        String text=sentence.path("text").textValue();
        if(text.isBlank())return new TimedTranscript(clipStartMs,clipEndMs,"",TimedTranscript.State.NO_TRANSCRIPT,TimedTranscript.Precision.CLIP,List.of());
        long duration=clipEndMs-clipStartMs;
        long begin=number(sentence,"begin_time"),end=number(sentence,"end_time");
        if(begin<0 || end<=begin || end>duration)throw new IllegalArgumentException("ASR sentence outside audio");
        JsonNode words=sentence.path("words");
        if(!words.isArray() || words.isEmpty() || words.size()>2048)throw new IllegalArgumentException("Missing ASR word timestamps");
        long previous=begin;
        for(JsonNode word:words) {
            long start=number(word,"begin_time"),stop=number(word,"end_time");
            if(start<previous || stop<=start || start<begin || stop>end || !word.path("text").isTextual())
                throw new IllegalArgumentException("Invalid ASR word timestamps");
            previous=stop;
        }
        // Sentence text remains intact: do not pretend individual word timestamps are independent sentences.
        return new TimedTranscript(clipStartMs,clipEndMs,text,TimedTranscript.State.AVAILABLE,TimedTranscript.Precision.SEGMENT,
                List.of(new TimedTranscript.Segment(Math.addExact(clipStartMs,begin),Math.addExact(clipStartMs,end),text)));
    }
    private static long number(JsonNode value,String name) {
        JsonNode field=value.path(name);
        if(!field.isIntegralNumber() || !field.canConvertToLong())throw new IllegalArgumentException("Invalid ASR timestamp");
        return field.longValue();
    }
}
