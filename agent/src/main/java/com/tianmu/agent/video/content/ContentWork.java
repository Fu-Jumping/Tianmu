package com.tianmu.agent.video.content;

import java.time.Instant;
import java.util.*;

/** Server-owned production contracts; they contain no user bearer token. */
public final class ContentWork {
    private ContentWork() {}
    public enum Stage { TRANSCRIBE, VISION, INDEX, SUMMARY }
    public enum Status { QUEUED, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, EXPIRED, REVOKED }
    public enum Checkpoint { ADMISSION, CLAIM, DISPATCH, COMMIT }
    public record Unit(ContentAssetKey asset, Stage stage, String segmentId, String stageVersion,
                       long startMs, long endMs) {
        public Unit {
            Objects.requireNonNull(asset); Objects.requireNonNull(stage);
            identifier(segmentId); identifier(stageVersion);
            if (startMs < 0 || endMs <= startMs) throw new IllegalArgumentException("Invalid work range");
        }
    }
    /** Opaque grant identifier is resolved and revalidated by the business authority. */
    public record Grant(UUID id, ContentAssetKey asset, Instant expiresAt) {
        public Grant { Objects.requireNonNull(id); Objects.requireNonNull(asset); Objects.requireNonNull(expiresAt); }
    }
    public record Receipt(String reference, String digest) {
        public Receipt {
            identifier(reference);
            if (digest == null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid result digest");
        }
    }
    public record Job(UUID id, Unit unit, Grant grant, Status status, int attempts, int maxAttempts,
                      long fence, String leaseOwner, Instant leaseUntil, Instant deadline, Receipt result, String errorCode) {}
    public record Lease(UUID jobId, long fence, String worker) {
        public Lease { Objects.requireNonNull(jobId); identifier(worker); if (fence < 1) throw new IllegalArgumentException("Invalid fence"); }
    }
    static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.:-]{1,128}")) throw new IllegalArgumentException("Invalid content identifier");
    }
}
