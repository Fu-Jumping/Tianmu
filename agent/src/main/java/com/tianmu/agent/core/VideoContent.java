package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

/** Playback times are milliseconds; these are sampled model observations, not verified causes. */
public final class VideoContent {
    private VideoContent() {}
    public record Source(long videoId, String sourceVersion, long durationMs) {}
    public record Frame(long atMs, String jpegBase64) {}
    public record Media(Source source, long startMs, long endMs, List<Frame> frames, String audioBase64) {}
    public record Moment(long atMs, String observation) {}
    public record TranscriptSegment(long startMs, long endMs, String text) {
        public TranscriptSegment {
            if (startMs < 0 || endMs <= startMs || text == null || text.isBlank() || text.length() > 12000)
                throw new IllegalArgumentException("Invalid transcript segment");
        }
    }
    public record Facts(String kind, long videoId, String sourceVersion, long startMs, long endMs,
                        String summary, List<Moment> moments, String transcript, String coverage,
                        List<Long> sampledAtMs, String model, Instant analyzedAt,
                        String transcriptPrecision, List<TranscriptSegment> transcriptSegments) {
        public Facts(String kind, long videoId, String sourceVersion, long startMs, long endMs,
                     String summary, List<Moment> moments, String transcript, String coverage,
                     List<Long> sampledAtMs, String model, Instant analyzedAt) {
            this(kind, videoId, sourceVersion, startMs, endMs, summary, moments, transcript, coverage,
                    sampledAtMs, model, analyzedAt, null, null);
        }
        public Facts {
            if (!"video-content-v1".equals(kind) || videoId <= 0 || sourceVersion == null || sourceVersion.length() > 128
                    || startMs < 0 || endMs <= startMs || summary == null || summary.isBlank() || summary.length() > 4000
                    || transcript == null || transcript.length() > 12000 || model == null || model.length() > 160)
                throw new IllegalArgumentException("Invalid video evidence");
            moments = List.copyOf(moments); sampledAtMs = List.copyOf(sampledAtMs);
            if (moments.size() > 12 || sampledAtMs.isEmpty() || sampledAtMs.size() > 12 || analyzedAt == null
                    || !Set.of("SAMPLED_FRAMES", "SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT").contains(coverage))
                throw new IllegalArgumentException("Invalid video coverage");
            for (long at : sampledAtMs) if (at < startMs || at >= endMs) throw new IllegalArgumentException("Frame outside range");
            for (Moment moment : moments) if (!sampledAtMs.contains(moment.atMs()) || moment.observation() == null
                    || moment.observation().isBlank() || moment.observation().length() > 600)
                throw new IllegalArgumentException("Unanchored video observation");
            transcriptPrecision = transcriptPrecision == null ? "CLIP" : transcriptPrecision;
            transcriptSegments = transcriptSegments == null ? List.of() : List.copyOf(transcriptSegments);
            if (!Set.of("CLIP", "SEGMENT").contains(transcriptPrecision) || transcriptSegments.size() > 2048
                    || transcript.isBlank() && !transcriptSegments.isEmpty()
                    || "SEGMENT".equals(transcriptPrecision) && transcriptSegments.isEmpty())
                throw new IllegalArgumentException("Invalid transcript precision");
            long previous = startMs; int total = 0;
            for (TranscriptSegment segment : transcriptSegments) {
                total += segment.text().length();
                if (segment.startMs() < previous || segment.endMs() > endMs || total > 12000)
                    throw new IllegalArgumentException("Transcript segment outside clip or unordered");
                previous = segment.startMs();
            }
            if ("CLIP".equals(transcriptPrecision) && !transcriptSegments.isEmpty()
                    && (transcriptSegments.size() != 1 || transcriptSegments.get(0).startMs() != startMs
                    || transcriptSegments.get(0).endMs() != endMs))
                throw new IllegalArgumentException("Clip precision cannot invent segment timestamps");
        }
    }
}
