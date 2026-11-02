package ai.core.session.transcript;

import ai.core.utils.JsonUtil;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read side of the transcript: whole-turn paging over base projection + file records, total turn
 * count and blob payload lookup for the desktop.
 *
 * @author stephen
 */
public final class TranscriptReader {
    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final long OVERSIZED_BYTES = 50L * 1024 * 1024;

    public record PartialTurn(String turnId, String side) {
    }

    public record Page(List<TranscriptRecord> records, int totalTurns, long maxSeq, Long nextBeforeSeq,
                       List<PartialTurn> partialTurns, boolean oversized, String source) {
    }

    public record Payload(String text, String path, long size) {
    }

    private final TranscriptStore store;

    public TranscriptReader(TranscriptStore store) {
        this.store = store;
    }

    public Page page(long beforeSeq, int turns, int maxRecords) {
        var groups = turns();
        var eligible = eligible(groups);
        int maxTurns = Math.max(1, turns);
        var builder = new PageBuilder(beforeSeq, maxTurns, maxRecords);
        boolean more = false;
        for (var i = eligible.size() - 1; i >= 0; i--) {
            if (!builder.add(eligible.get(i))) {
                more = true;
                break;
            }
        }
        return builder.build(eligible.size(), store.maxSeq(), isOversized(), more);
    }

    public int totalTurns() {
        return eligible(turns()).size();
    }

    public long maxSeq() {
        return store.maxSeq();
    }

    public Payload payload(long seq, String field, Integer index) {
        var record = store.find(seq).orElse(null);
        if (record == null) return null;
        var ref = record.text(field + "_ref");
        if (ref != null) return blob(ref);
        var inline = record.text(field);
        if (inline != null) return new Payload(inline, null, inline.length());
        return nestedPayload(record, field, index);
    }

    private Payload nestedPayload(TranscriptRecord record, String field, Integer index) {
        if (!"inputs".equals(field) || index == null) return null;
        var inputs = record.objects("inputs");
        if (inputs == null || index < 0 || index >= inputs.size()) return null;
        var input = inputs.get(index);
        var ref = value(input, "text_ref");
        if (ref != null) return blob(ref);
        var text = value(input, "text");
        return text == null ? null : new Payload(text, null, text.length());
    }

    private Payload blob(String ref) {
        var path = store.blobPath(ref);
        if (path == null || !Files.isRegularFile(path)) return null;
        var text = store.readBlob(ref);
        if (text == null) return null;
        var size = text.length();
        if (size > MAX_PAGE_BYTES) return new Payload(null, path.toString(), size);
        return new Payload(text, path.toString(), size);
    }

    private boolean isOversized() {
        return store.fileSize() > OVERSIZED_BYTES;
    }

    private List<List<TranscriptRecord>> turns() {
        var groups = new ArrayList<List<TranscriptRecord>>();
        List<TranscriptRecord> current = null;
        for (var record : store.allRecords()) {
            if (TranscriptKinds.SESSION_START.equals(record.kind) || TranscriptKinds.REWIND.equals(record.kind)) continue;
            if (TranscriptKinds.TURN_START.equals(record.kind)) {
                current = new ArrayList<>();
                groups.add(current);
            }
            if (current != null) current.add(record);
        }
        return groups;
    }

    private List<List<TranscriptRecord>> eligible(List<List<TranscriptRecord>> groups) {
        var rewindTo = latestRewind();
        var eligible = new ArrayList<List<TranscriptRecord>>();
        for (var group : groups) {
            var turn = group.isEmpty() ? null : group.getFirst();
            if (turn == null) continue;
            var seq = turnSeq(turn.turnId);
            if (rewindTo >= 0 && seq >= rewindTo) continue;
            eligible.add(group);
        }
        return eligible;
    }

    private long latestRewind() {
        long to = -1;
        for (var record : store.records()) {
            if (!TranscriptKinds.REWIND.equals(record.kind)) continue;
            var value = record.number("to_seq");
            if (value != null) to = value;
        }
        return to;
    }

    private static long turnSeq(String turnId) {
        if (turnId == null || !turnId.startsWith("t")) return -1;
        try {
            return Long.parseLong(turnId.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String value(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private final class PageBuilder {
        private final long beforeSeq;
        private final int maxTurns;
        private final int maxRecords;
        private final List<List<TranscriptRecord>> selected = new ArrayList<>();
        private int records;
        private long bytes;

        PageBuilder(long beforeSeq, int maxTurns, int maxRecords) {
            this.beforeSeq = beforeSeq;
            this.maxTurns = maxTurns;
            this.maxRecords = Math.max(1, maxRecords);
        }

        boolean add(List<TranscriptRecord> group) {
            if (selected.size() >= maxTurns) return false;
            var slice = slice(group);
            if (slice.isEmpty()) return true;
            var size = bytes(slice);
            if (!selected.isEmpty() && (records + slice.size() > maxRecords || bytes + size > MAX_PAGE_BYTES)) {
                return false;
            }
            selected.add(slice);
            records += slice.size();
            bytes += size;
            return true;
        }

        private List<TranscriptRecord> slice(List<TranscriptRecord> group) {
            var slice = new ArrayList<TranscriptRecord>();
            for (var record : group) {
                if (record.seq < beforeSeq) slice.add(record);
            }
            var start = slice.size();
            long size = 0;
            while (start > 0 && slice.size() - start < maxRecords) {
                var lineSize = JsonUtil.toJson(slice.get(start - 1)).length();
                if (size + lineSize > MAX_PAGE_BYTES) break;
                start--;
                size += lineSize;
            }
            return new ArrayList<>(slice.subList(start, slice.size()));
        }

        private long bytes(List<TranscriptRecord> slice) {
            long size = 0;
            for (var record : slice) {
                size += JsonUtil.toJson(record.toMap()).length();
            }
            return size;
        }

        Page build(int totalTurns, long maxSeq, boolean oversized, boolean more) {
            var page = new ArrayList<TranscriptRecord>();
            var partial = new ArrayList<PartialTurn>();
            for (var group : selected) {
                page.addAll(0, group);
            }
            if (!selected.isEmpty()) {
                addPartial(partial, selected.getLast(), true);
                addPartial(partial, selected.getFirst(), false);
            }
            page.sort((a, b) -> Long.compare(a.seq, b.seq));
            boolean headPartial = !selected.isEmpty() && isHeadPartial(selected.getLast());
            Long next = (more || headPartial) && !page.isEmpty() ? page.getFirst().seq : null;
            return new Page(page, totalTurns, maxSeq, next, partial, oversized, source(page));
        }

        private void addPartial(List<PartialTurn> partial, List<TranscriptRecord> slice, boolean head) {
            if (slice.isEmpty()) return;
            var turnId = slice.getFirst().turnId;
            var complete = head ? isHeadPartial(slice) : isTailPartial(slice);
            if (!complete) return;
            partial.add(new PartialTurn(turnId, head ? "head" : "tail"));
        }

        private boolean isHeadPartial(List<TranscriptRecord> slice) {
            var first = slice.getFirst();
            var turnSeq = turnSeq(first.turnId);
            return turnSeq >= 0 && !TranscriptKinds.TURN_START.equals(first.kind);
        }

        private boolean isTailPartial(List<TranscriptRecord> slice) {
            return slice.stream().noneMatch(record -> TranscriptKinds.TURN_COMPLETE.equals(record.kind)
                || TranscriptKinds.ERROR.equals(record.kind) && Boolean.TRUE.equals(record.bool("terminal")));
        }

        private String source(List<TranscriptRecord> page) {
            boolean projection = false;
            boolean transcript = false;
            for (var record : page) {
                if (TranscriptKinds.SESSION_START.equals(record.kind) || TranscriptKinds.REWIND.equals(record.kind)) continue;
                if (Boolean.TRUE.equals(record.synthetic) || record.seq < 0) {
                    projection = true;
                } else {
                    transcript = true;
                }
            }
            if (projection && transcript) return "mixed";
            return projection ? "projection" : "transcript";
        }
    }
}
