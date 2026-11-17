package com.tianmu.agent.video.notes;

import com.tianmu.agent.video.content.ContentAssetKey;
import java.util.*;

/** Server-verified processing evidence, never an LLM assertion of completeness. Ranges are half-open. */
public record NotesCoverage(ContentAssetKey asset, long durationMs, String policyDigest, List<Unit> units) {
    public enum Audio { TRANSCRIBED, NO_SPEECH, NO_AUDIO_TRACK, FAILED }
    public enum Visual { SAMPLED, NO_VISUAL_TRACK, FAILED }
    public record Range(long startMs, long endMs) {
        public Range { if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("Invalid notes range"); }
        public boolean contains(Range other) { return startMs <= other.startMs && endMs >= other.endMs; }
    }
    public record Unit(String id, ContentAssetKey asset, Range owned, Range dependency,
                       Audio audio, Visual visual, String audioDigest, String visualDigest, List<Long> sampledAtMs) {
        public Unit {
            identifier(id); Objects.requireNonNull(asset); Objects.requireNonNull(owned); Objects.requireNonNull(dependency);
            Objects.requireNonNull(audio); Objects.requireNonNull(visual);
            if (!dependency.contains(owned)) throw new IllegalArgumentException("Dependency must contain owned range");
            if (audio != Audio.FAILED) digest(audioDigest); else audioDigest = "";
            if (visual != Visual.FAILED) digest(visualDigest); else visualDigest = "";
            sampledAtMs = List.copyOf(sampledAtMs);
            if (sampledAtMs.size() > 256) throw new IllegalArgumentException("Frame manifest too large");
            long previous = -1;
            for (long sample : sampledAtMs) {
                if (sample < dependency.startMs || sample >= dependency.endMs || sample <= previous)
                    throw new IllegalArgumentException("Frames must be ordered within their actual dependency");
                previous = sample;
            }
            if (visual == Visual.SAMPLED && sampledAtMs.stream().noneMatch(t -> t >= owned.startMs && t < owned.endMs))
                throw new IllegalArgumentException("No frame in owned range");
            if (visual != Visual.SAMPLED && !sampledAtMs.isEmpty()) throw new IllegalArgumentException("Unexpected frames");
            if (audio == Audio.NO_AUDIO_TRACK && visual == Visual.NO_VISUAL_TRACK)
                throw new IllegalArgumentException("Media has no observable modality");
        }
        public boolean processed() { return audio != Audio.FAILED && visual != Visual.FAILED; }
    }
    public NotesCoverage {
        Objects.requireNonNull(asset); digest(policyDigest);
        if (durationMs <= 0) throw new IllegalArgumentException("Invalid video duration");
        units = List.copyOf(units);
        if (units.isEmpty() || units.size() > 10000) throw new IllegalArgumentException("Invalid coverage size");
        Set<String> ids = new HashSet<>(); long previousEnd = 0;
        for (Unit unit : units) {
            if (!unit.asset.equals(asset) || !ids.add(unit.id) || unit.owned.startMs < previousEnd
                    || unit.dependency.endMs > durationMs)
                throw new IllegalArgumentException("Mixed identity, duplicate, overlap or out-of-video dependency");
            previousEnd = unit.owned.endMs;
        }
    }
    /** Necessary processing condition only; does not prove all events or claims were captured. */
    public boolean fullyProcessed() {
        long next = 0;
        for (Unit unit : units) {
            if (unit.owned.startMs != next || !unit.processed()) return false;
            next = unit.owned.endMs;
        }
        return next == durationMs;
    }
    static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid notes digest");
    }
    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid notes identifier");
    }
}
