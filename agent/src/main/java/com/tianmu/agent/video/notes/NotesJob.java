package com.tianmu.agent.video.notes;

import com.tianmu.agent.video.content.ContentAssetKey;
import com.tianmu.agent.video.content.ContentWork;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Durable contracts only; callers must authorize admission and every consumption separately. */
public final class NotesJob {
    private NotesJob() {}
    public enum Status { QUEUED, RUNNING, READY, PARTIAL, FAILED, EXPIRED, REVOKED, STALE }
    public enum CallState { PREPARED, DISPATCHING, UNKNOWN, UNKNOWN_CHARGED, RESULT_STAGED, COMMITTED }
    public record Policy(String language, String schemaVersion, String analysisVersion, String generationVersion,
                         long segmentMs, long overlapMs, long budgetMicros) {
        public Policy {
            text(language); text(schemaVersion); text(analysisVersion); text(generationVersion);
            if (segmentMs < 1000 || segmentMs > 110000 || overlapMs < 0 || overlapMs > 5000
                    || budgetMicros <= 0) throw new IllegalArgumentException("Invalid notes policy");
        }
    }
    public record Create(String tenantId, String userId, ContentAssetKey asset, long durationMs,
                         Policy policy, String idempotencyKey, Instant deadline) {
        public Create {
            text(tenantId); text(userId); text(idempotencyKey); Objects.requireNonNull(asset);
            Objects.requireNonNull(policy); Objects.requireNonNull(deadline);
            text(asset.sourceVersion()); text(asset.authorizationDomain());
            if (!tenantId.equals(asset.tenantId()) || durationMs <= 0 || durationMs > 86400000)
                throw new IllegalArgumentException("Invalid notes admission");
        }
    }
    public record Job(UUID id, Create request, String rootKey, Status status, long version, int generation,
                      String planDigest, long reservedMicros, long usedMicros, String documentDigest) {}
    public record PlanUnit(String unitKey, ContentWork.Stage stage, long startMs, long endMs,
                           long dependencyStartMs, long dependencyEndMs, String inputDigest) {
        public PlanUnit {
            text(unitKey); Objects.requireNonNull(stage); digest(inputDigest);
            if (startMs < 0 || endMs <= startMs || dependencyStartMs < 0 || dependencyStartMs > startMs
                    || dependencyEndMs < endMs) throw new IllegalArgumentException("Invalid plan range");
        }
        public String segmentId(UUID root) {
            return "notes:" + hash(root.toString(), unitKey, stage.name(), Long.toString(startMs),
                    Long.toString(endMs), Long.toString(dependencyStartMs), Long.toString(dependencyEndMs), inputDigest);
        }
    }
    public record Call(UUID id, UUID rootId, int generation, String operationKey, CallState state,
                       long reservedMicros, String resultRef, String resultDigest) {}
    public static List<PlanUnit> sourcePlan(UUID root, long duration, Policy policy, String sourceDigest) {
        Objects.requireNonNull(root); digest(sourceDigest);
        if (duration <= 0 || duration > 86400000) throw new IllegalArgumentException("Invalid duration");
        List<PlanUnit> units = new ArrayList<>();
        for (long start = 0; start < duration; start += policy.segmentMs()) {
            long end = Math.min(duration, start + policy.segmentMs());
            for (var stage : List.of(ContentWork.Stage.TRANSCRIBE, ContentWork.Stage.VISION))
                units.add(new PlanUnit(stage.name().toLowerCase(Locale.ROOT) + ":" + start, stage, start, end,
                        Math.max(0, start-policy.overlapMs()), Math.min(duration, end+policy.overlapMs()), sourceDigest));
        }
        return List.copyOf(units);
    }
    public static String hash(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static void text(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 128
                || value.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid notes identifier");
    }
    static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid notes digest");
    }
}
