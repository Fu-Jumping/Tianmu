package com.tianmu.agent.video.content;

import java.util.*;

/** Checks source dependencies, not merely the time printed on a summary or citation.
 * This is a boundary check after authorization; passing it does not authorize a read.
 */
public final class ContentBoundary {
    private ContentBoundary() {}
    public enum Policy { WATCHED_ONLY, EXPLICIT_RANGE, WHOLE_VIDEO }
    public enum Kind { TRANSCRIPT, FRAME, DERIVED, HISTORY }
    public record Window(ContentAssetKey asset, Policy policy, long startMs, long endMs, long durationMs) {
        public Window {
            Objects.requireNonNull(asset); Objects.requireNonNull(policy);
            if (startMs < 0 || endMs < startMs || endMs > durationMs || durationMs <= 0
                    || policy == Policy.WHOLE_VIDEO && (startMs != 0 || endMs != durationMs))
                throw new IllegalArgumentException("Invalid content window");
        }
    }
    /** Source ranges are half-open; frames use an instantaneous startMs == endMs. */
    public record Material(String id, ContentAssetKey asset, Kind kind, long startMs, long endMs,
                           List<String> dependencies) {
        public Material {
            if (id == null || id.isBlank() || id.length() > 160 || startMs < 0 || endMs < startMs)
                throw new IllegalArgumentException("Invalid content provenance");
            Objects.requireNonNull(asset); Objects.requireNonNull(kind);
            dependencies = List.copyOf(dependencies);
            if (dependencies.size() > 128 || new HashSet<>(dependencies).size() != dependencies.size()
                    || dependencies.stream().anyMatch(d -> d == null || d.isBlank())
                    || (kind == Kind.DERIVED || kind == Kind.HISTORY) && dependencies.isEmpty()
                    || (kind == Kind.TRANSCRIPT || kind == Kind.FRAME) && !dependencies.isEmpty()
                    || kind == Kind.TRANSCRIPT && startMs == endMs
                    || kind == Kind.FRAME && startMs != endMs)
                throw new IllegalArgumentException("Invalid content dependencies");
        }
    }
    public enum Rejection { MISSING_PROVENANCE, SOURCE_MISMATCH, OUTSIDE_WINDOW, CYCLIC_PROVENANCE, GRAPH_LIMIT }
    public record Decision(boolean allowed, Rejection rejection) {
        private static Decision allow() { return new Decision(true, null); }
        private static Decision reject(Rejection reason) { return new Decision(false, reason); }
    }
    /** Unknown, cyclic and cross-version dependencies fail closed. Does not mutate stored materials. */
    public static Decision check(String candidateId, Window window, Collection<Material> materials) {
        Objects.requireNonNull(window); Objects.requireNonNull(materials);
        if (materials.size() > 4096) return Decision.reject(Rejection.GRAPH_LIMIT);
        Map<String,Material> index = new HashMap<>();
        for (Material m : materials) {
            if (m == null || index.putIfAbsent(m.id(), m) != null)
                return Decision.reject(Rejection.MISSING_PROVENANCE);
        }
        return visit(candidateId, window, index, new HashSet<>(), new HashSet<>(), 0);
    }
    private static Decision visit(String id, Window w, Map<String,Material> index,
                                   Set<String> visiting, Set<String> checked, int depth) {
        if (depth > 128) return Decision.reject(Rejection.GRAPH_LIMIT);
        if (visiting.contains(id)) return Decision.reject(Rejection.CYCLIC_PROVENANCE);
        if (checked.contains(id)) return Decision.allow();
        Material m = index.get(id);
        if (m == null) return Decision.reject(Rejection.MISSING_PROVENANCE);
        if (!m.asset().equals(w.asset())) return Decision.reject(Rejection.SOURCE_MISMATCH);
        if (m.startMs() < w.startMs() || m.endMs() > w.endMs()
                || m.kind() == Kind.FRAME && m.startMs() >= w.durationMs()
                || m.kind() == Kind.FRAME && w.policy() == Policy.EXPLICIT_RANGE && m.startMs() == w.endMs())
            return Decision.reject(Rejection.OUTSIDE_WINDOW);
        visiting.add(id);
        for (String dependency : m.dependencies()) {
            Decision result = visit(dependency, w, index, visiting, checked, depth + 1);
            if (!result.allowed()) return result;
        }
        visiting.remove(id); checked.add(id);
        return Decision.allow();
    }
}
