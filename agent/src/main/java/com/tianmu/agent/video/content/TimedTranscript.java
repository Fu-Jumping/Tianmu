package com.tianmu.agent.video.content;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.*;
import java.util.*;

/** Raw ASR output on the site's playback timeline. Empty output does not prove silence. */
public record TimedTranscript(long clipStartMs, long clipEndMs, String rawText,
                              State state, Precision precision, List<Segment> segments) {
    public enum State { AVAILABLE, NO_TRANSCRIPT }
    public enum Precision { SEGMENT, CLIP }
    public record Segment(long startMs, long endMs, String text) {
        public Segment {
            if (startMs < 0 || endMs <= startMs || text == null || text.isBlank() || text.length() > 12000)
                throw new IllegalArgumentException("Invalid transcript segment");
        }
    }
    public TimedTranscript {
        Objects.requireNonNull(state); Objects.requireNonNull(precision);
        if (clipStartMs < 0 || clipEndMs <= clipStartMs || rawText == null || rawText.length() > 12000)
            throw new IllegalArgumentException("Invalid transcript clip");
        segments = List.copyOf(segments);
        if (segments.size() > 2048 || (state == State.AVAILABLE) != !segments.isEmpty()
                || state == State.NO_TRANSCRIPT && !rawText.isBlank())
            throw new IllegalArgumentException("Invalid transcript state");
        long previousStart = clipStartMs;
        for (Segment segment : segments) {
            if (segment.startMs() < previousStart || segment.endMs() > clipEndMs)
                throw new IllegalArgumentException("Transcript outside clip or unordered");
            previousStart = segment.startMs();
        }
        if (precision == Precision.CLIP && !segments.isEmpty()
                && (segments.size() != 1 || segments.get(0).startMs() != clipStartMs
                    || segments.get(0).endMs() != clipEndMs))
            throw new IllegalArgumentException("Clip-only transcript cannot invent sentence timing");
    }
    /** Adapter contract: optional segments use seconds relative to the submitted audio clip. */
    public static TimedTranscript fromRelativeSeconds(JsonNode response, long startMs, long endMs) {
        if (response == null || !response.isObject()) throw new IllegalArgumentException("Invalid ASR response");
        JsonNode textNode = response.get("text");
        if (textNode != null && !textNode.isTextual()) throw new IllegalArgumentException("Invalid ASR text");
        String raw = textNode == null ? "" : textNode.textValue();
        JsonNode values = response.get("segments");
        if (values != null && (!values.isArray() || values.size() > 2048))
            throw new IllegalArgumentException("Invalid ASR segments");
        List<Segment> segments = new ArrayList<>();
        if (values != null) for (JsonNode value : values) {
            if (!value.path("text").isTextual() || !value.path("start").isNumber() || !value.path("end").isNumber())
                throw new IllegalArgumentException("Invalid ASR segment");
            long from = Math.addExact(startMs, millis(value.get("start"), RoundingMode.FLOOR));
            long to = Math.addExact(startMs, millis(value.get("end"), RoundingMode.CEILING));
            segments.add(new Segment(from, to, value.get("text").textValue()));
        }
        if (!segments.isEmpty()) return new TimedTranscript(startMs, endMs, raw, State.AVAILABLE, Precision.SEGMENT, segments);
        return new TimedTranscript(startMs, endMs, raw, raw.isBlank() ? State.NO_TRANSCRIPT : State.AVAILABLE,
                Precision.CLIP, raw.isBlank() ? List.of() : List.of(new Segment(startMs, endMs, raw)));
    }
    private static long millis(JsonNode value, RoundingMode rounding) {
        BigDecimal seconds = value.decimalValue();
        if (seconds.signum() < 0) throw new IllegalArgumentException("Negative ASR timestamp");
        return seconds.movePointRight(3).setScale(0, rounding).longValueExact();
    }
}
