package com.tianmu.agent.video.content;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.tianmu.agent.video.content.ContentBoundary.*;
import static org.junit.jupiter.api.Assertions.*;

class ContentBoundaryTest {
    private final ContentAssetKey asset = new ContentAssetKey("site", 800000026087195706L, "v1", "public", 2);
    private Material evidence(String id, long from, long to) {
        return new Material(id, asset, Kind.TRANSCRIPT, from, to, List.of());
    }
    private Material derived(String id, Kind kind, String... dependencies) {
        return new Material(id, asset, kind, 0, 1000, List.of(dependencies));
    }
    private Window current() { return new Window(asset, Policy.WATCHED_ONLY, 0, 300000, 1800000); }

    @Test void rejectsFutureAnswerHiddenBehindAnEarlierSummaryAndEarlierHistory() {
        var future = evidence("solution", 310000, 320000);
        var summary = derived("chapter", Kind.DERIVED, "solution");
        var history = derived("old-answer", Kind.HISTORY, "chapter");
        assertEquals(Rejection.OUTSIDE_WINDOW, check("old-answer", current(), List.of(future, summary, history)).rejection());
        var whole = new Window(asset, Policy.WHOLE_VIDEO, 0, 1800000, 1800000);
        assertTrue(check("old-answer", whole, List.of(future, summary, history)).allowed());
    }
    @Test void rejectsWholeCrossingTranscriptRatherThanTruncatingItsWords() {
        var crossing = evidence("sentence", 295000, 305000);
        assertFalse(check("sentence", current(), List.of(crossing)).allowed());
        var clipped = evidence("retranscribed", 295000, 300000);
        assertTrue(check("retranscribed", current(), List.of(clipped)).allowed());
    }
    @Test void checksSourceVersionTenantAuthorizationDomainAndRevocationEpoch() {
        var different = List.of(
                new ContentAssetKey("other", asset.videoId(), "v1", "public", 2),
                new ContentAssetKey("site", asset.videoId(), "v2", "public", 2),
                new ContentAssetKey("site", asset.videoId(), "v1", "private", 2),
                new ContentAssetKey("site", asset.videoId(), "v1", "public", 3));
        for (var source : different) {
            var m = new Material("item", source, Kind.FRAME, 1000, 1000, List.of());
            assertEquals(Rejection.SOURCE_MISMATCH, check("item", current(), List.of(m)).rejection());
        }
    }
    @Test void unknownDuplicateAndCyclicProvenanceCannotBecomeEligibleContext() {
        var missing = derived("summary", Kind.DERIVED, "missing");
        assertEquals(Rejection.MISSING_PROVENANCE, check("summary", current(), List.of(missing)).rejection());
        var first = derived("first", Kind.DERIVED, "second");
        var second = derived("second", Kind.HISTORY, "first");
        assertEquals(Rejection.CYCLIC_PROVENANCE, check("first", current(), List.of(first, second)).rejection());
        var item = evidence("same", 1000, 2000);
        assertEquals(Rejection.MISSING_PROVENANCE, check("same", current(), List.of(item, item)).rejection());
        assertThrows(IllegalArgumentException.class, () -> derived("no-origin", Kind.HISTORY));
    }
    @Test void validDependenciesCanShareACommonAncestorAndFramesRespectExplicitEnd() {
        var raw = evidence("raw", 1000, 2000);
        var a = derived("a", Kind.DERIVED, "raw");
        var b = derived("b", Kind.DERIVED, "raw");
        var h = derived("history", Kind.HISTORY, "a", "b");
        assertTrue(check("history", current(), List.of(raw, a, b, h)).allowed());
        var frame = new Material("frame", asset, Kind.FRAME, 300000, 300000, List.of());
        assertTrue(check("frame", current(), List.of(frame)).allowed());
        var explicit = new Window(asset, Policy.EXPLICIT_RANGE, 290000, 300000, 1800000);
        assertFalse(check("frame", explicit, List.of(frame)).allowed());
    }
}
