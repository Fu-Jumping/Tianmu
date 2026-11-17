package com.tianmu.agent.video.notes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.tianmu.agent.video.notes.NotesCoverage.*;

/** Versioned document contract. Text is data: consumers must use text nodes, never raw HTML. */
public record NotesDocument(int schemaVersion, String title, String overview, List<String> keyPoints,
                            List<Section> sections, List<String> conclusions, NotesCoverage coverage) {
    public enum Kind { PARAGRAPH, LIST, TABLE, MATH, CODE }
    public enum Modality { AUDIO, VISUAL }
    public record Citation(String unitId, Modality modality, Range range, String sourceDigest) {
        public Citation { identifier(unitId); Objects.requireNonNull(modality); Objects.requireNonNull(range); digest(sourceDigest); }
    }
    public record Block(Kind kind, String text, List<String> items, List<List<String>> rows, List<Citation> citations) {
        public Block {
            Objects.requireNonNull(kind); text = checked(text, 16000, true);
            items = strings(items, 200, 4000); citations = List.copyOf(citations);
            if (citations.isEmpty() || citations.size() > 100) throw new IllegalArgumentException("Block needs bounded citations");
            rows = rows.stream().map(row -> strings(row, 30, 4000)).toList();
            if (rows.size() > 200) throw new IllegalArgumentException("Table too large");
            if (kind == Kind.TABLE) {
                int width = rows.isEmpty() ? 0 : rows.get(0).size();
                if (width == 0 || rows.stream().anyMatch(r -> r.size() != width)
                        || !text.isEmpty() || !items.isEmpty()) throw new IllegalArgumentException("Invalid table");
            } else if (kind == Kind.LIST) {
                if (items.isEmpty() || !text.isEmpty() || !rows.isEmpty()) throw new IllegalArgumentException("Invalid list");
            } else if (text.isBlank() || !items.isEmpty() || !rows.isEmpty()) throw new IllegalArgumentException("Invalid text block");
        }
    }
    public record Section(String id, String title, Range range, List<Block> blocks, List<String> uncertainties) {
        public Section {
            identifier(id); title = checked(title, 200, false); Objects.requireNonNull(range);
            blocks = List.copyOf(blocks); uncertainties = strings(uncertainties, 100, 2000);
            if (blocks.isEmpty() || blocks.size() > 500) throw new IllegalArgumentException("Invalid section size");
        }
    }
    public NotesDocument {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported notes schema");
        title = checked(title, 300, false); overview = checked(overview, 6000, false);
        keyPoints = strings(keyPoints, 50, 2000); conclusions = strings(conclusions, 50, 4000);
        Objects.requireNonNull(coverage); sections = List.copyOf(sections);
        if (sections.isEmpty() || sections.size() > 500) throw new IllegalArgumentException("Invalid section count");
        Map<String, Unit> sources = new HashMap<>(); coverage.units().forEach(u -> sources.put(u.id(), u));
        Set<String> ids = new HashSet<>(); long previous = -1; long size = title.length() + overview.length();
        for (Section section : sections) {
            if (!ids.add(section.id) || section.range.startMs() < previous || section.range.endMs() > coverage.durationMs())
                throw new IllegalArgumentException("Invalid section ordering or range");
            previous = section.range.startMs(); size += section.title.length();
            for (Block block : section.blocks) {
                size += block.text.length() + block.items.stream().mapToLong(String::length).sum()
                        + block.rows.stream().flatMap(List::stream).mapToLong(String::length).sum();
                for (Citation citation : block.citations) {
                    Unit unit = sources.get(citation.unitId);
                    if (unit == null || !unit.dependency().contains(citation.range)
                            || !section.range.contains(citation.range)) throw new IllegalArgumentException("Invalid citation range or source");
                    String expected = citation.modality == Modality.AUDIO ? unit.audioDigest() : unit.visualDigest();
                    if (!citation.sourceDigest.equals(expected)
                            || citation.modality == Modality.AUDIO && unit.audio() != Audio.TRANSCRIBED
                            || citation.modality == Modality.VISUAL && unit.visual() != Visual.SAMPLED)
                        throw new IllegalArgumentException("Citation does not reference observable evidence");
                    // V1 conservatively treats all audio evidence as CLIP precision. Segment precision
                    // requires a future verified segment registry, never a model-provided subrange.
                    if (citation.modality == Modality.AUDIO && !citation.range.equals(unit.dependency()))
                        throw new IllegalArgumentException("Audio citation must preserve full clip dependency");
                    if (citation.modality == Modality.VISUAL && unit.sampledAtMs().stream()
                            .noneMatch(t -> t >= citation.range.startMs() && t < citation.range.endMs()))
                        throw new IllegalArgumentException("Visual citation has no sampled frame");
                }
            }
        }
        size += keyPoints.stream().mapToLong(String::length).sum() + conclusions.stream().mapToLong(String::length).sum()
                + sections.stream().flatMap(s -> s.uncertainties.stream()).mapToLong(String::length).sum();
        if (size > 1_000_000) throw new IllegalArgumentException("Notes document too large; do not truncate");
    }
    public record QualityReceipt(String documentDigest, String policyDigest, boolean passed, List<String> majorIssues) {
        public QualityReceipt { digest(documentDigest); digest(policyDigest); majorIssues = strings(majorIssues, 100, 2000); }
    }
    /** Caller must perform live authorization and persist this decision with the Root CAS. */
    public void requirePublishable(QualityReceipt receipt, String expectedQualityPolicyDigest) {
        Objects.requireNonNull(receipt); digest(expectedQualityPolicyDigest);
        if (!coverage.fullyProcessed() || !receipt.passed || !receipt.majorIssues.isEmpty()
                || !receipt.policyDigest.equals(expectedQualityPolicyDigest) || !receipt.documentDigest.equals(contentDigest()))
            throw new IllegalArgumentException("Incomplete processing or mismatched quality receipt");
    }
    /** Unambiguous length-prefixed canonical encoding, independent of JSON field ordering. */
    public String contentDigest() {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            canonical(hash, this); return HexFormat.of().formatHex(hash.digest());
        } catch (ReflectiveOperationException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void canonical(MessageDigest hash, Object value) throws ReflectiveOperationException {
        if (value instanceof List<?> list) { token(hash, "list:" + list.size()); for (Object item : list) canonical(hash, item); }
        else if (value.getClass().isRecord()) {
            token(hash, value.getClass().getName());
            for (var part : value.getClass().getRecordComponents()) { token(hash, part.getName()); canonical(hash, part.getAccessor().invoke(value)); }
        } else token(hash, value.toString());
    }
    private static void token(MessageDigest hash, String value) {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        hash.update(java.nio.ByteBuffer.allocate(4).putInt(data.length).array()); hash.update(data);
    }
    public String plainText() {
        StringBuilder out = new StringBuilder(title).append("\n\n").append(coverage.fullyProcessed()
                ? "已处理完整视频；画面基于采样分析" : "部分笔记：视频内容尚未全部处理").append("\n\n").append(overview).append("\n");
        keyPoints.forEach(point -> out.append("\n• ").append(point));
        for (Section section : sections) {
            out.append("\n\n").append(section.title).append(" [").append(time(section.range.startMs())).append("–").append(time(section.range.endMs())).append("]\n");
            for (Block block : section.blocks) {
                if (!block.text.isEmpty()) out.append(block.text).append('\n');
                block.items.forEach(item -> out.append("• ").append(item).append('\n'));
                block.rows.forEach(row -> out.append(String.join(" | ", row)).append('\n'));
            }
            section.uncertainties.forEach(item -> out.append("待确认：").append(item).append('\n'));
        }
        conclusions.forEach(item -> out.append("\n").append(item));
        return out.append("\n\n来源：EchoFrame 视频 ").append(coverage.asset().videoId())
                .append(" · 版本 ").append(coverage.asset().sourceVersion()).toString();
    }
    private static String time(long ms) { return (ms / 60000) + ":" + String.format(Locale.ROOT, "%02d", ms / 1000 % 60); }
    private static List<String> strings(List<String> values, int count, int length) {
        if (values == null || values.size() > count) throw new IllegalArgumentException("Invalid text list");
        return values.stream().map(s -> checked(s, length, false)).toList();
    }
    private static String checked(String text, int max, boolean empty) {
        if (text == null || text.length() > max || !empty && text.isBlank()
                || text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t' && c != '\r'))
            throw new IllegalArgumentException("Invalid notes text");
        return text;
    }
}
