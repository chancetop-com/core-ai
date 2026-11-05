package ai.core.session.transcript;

import ai.core.llm.domain.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Owns the open-time transcript reconciliation: base projection, dangling turn/steer recovery,
 * gap materialization and the projection reset.
 *
 * @author stephen
 */
final class TranscriptStartup {
    record Result(List<TranscriptRecord> append, TranscriptUsage seed) {
    }

    private final TranscriptStore store;
    private final TranscriptMetadata metadata;
    private final List<Message> messages;
    private final List<TranscriptRecord> records;
    private final List<TranscriptRecord> append = new ArrayList<>();
    private long nextSeq;

    TranscriptStartup(TranscriptStore store, TranscriptMetadata metadata, List<Message> messages) {
        this.store = store;
        this.metadata = metadata;
        this.messages = messages;
        this.records = store.records();
        this.nextSeq = store.maxSeq() + 1;
    }

    Result start() {
        var base = baseRecord();
        var baseThrough = base == null ? messages.size() : (int) number(base, "projection_through", messages.size());
        var baseFingerprint = base == null ? null : base.text("projection_fingerprint");
        if (base == null) {
            writeSessionStart(baseThrough, baseFingerprint, null);
            baseFingerprint = writeBaseFingerprint(baseThrough);
        } else {
            writeSessionStart(baseThrough, baseFingerprint, null);
        }
        store.setBaseRecords(TranscriptProjection.base(messages, baseThrough));
        recoverOpenTurn();
        cancelDanglingSteers();
        var last = lastTerminalCount(baseThrough);
        var size = messages.size();
        if (size < last) {
            reset(baseThrough, size);
        } else if (size == baseThrough && !fingerprintMatches(baseFingerprint)) {
            writeReset(baseThrough);
        } else if (size > last) {
            gap(last, size);
        }
        return new Result(append, seed());
    }

    private TranscriptRecord baseRecord() {
        TranscriptRecord base = null;
        for (var record : records) {
            if (!TranscriptKinds.SESSION_START.equals(record.kind)) continue;
            if (Boolean.TRUE.equals(record.bool("projection_reset"))) {
                base = record;
            } else if (base == null && record.get("projection_from") == null) {
                base = record;
            }
        }
        return base;
    }

    private String writeBaseFingerprint(int through) {
        var value = through > 0 ? TranscriptProjection.fingerprint(messages.subList(0, through)) : null;
        var record = append.getLast();
        record.put("projection_fingerprint", value);
        return value;
    }

    private void writeSessionStart(int through, String fingerprint, Long from) {
        var record = write(TranscriptKinds.SESSION_START, null);
        record.put("workspace", metadata.workspace());
        record.put("engine_version", metadata.engineVersion());
        record.put("client", metadata.client());
        record.put("projection_through", (long) through);
        record.put("projection_fingerprint", fingerprint);
        record.put("projection_from", from);
    }

    private void recoverOpenTurn() {
        var terminalIds = new HashSet<String>();
        for (var record : records) {
            if (isTerminal(record)) terminalIds.add(record.turnId);
        }
        var rewinds = TranscriptRewind.of(records);
        TranscriptRecord open = null;
        for (var record : records) {
            if (!TranscriptKinds.TURN_START.equals(record.kind)) continue;
            if (rewinds.covers(record.turnId)) continue;
            if (terminalIds.contains(record.turnId)) continue;
            open = record;
        }
        if (open == null) return;
        var record = write(TranscriptKinds.TURN_COMPLETE, open.turnId);
        record.put("cancelled", Boolean.TRUE);
        record.put("message_count", (long) messages.size());
    }

    private void cancelDanglingSteers() {
        var closed = new HashSet<Long>();
        for (var record : records) {
            if (!TranscriptKinds.STEER.equals(record.kind)) continue;
            var ref = record.number("ref_seq");
            if (ref != null) closed.add(ref);
        }
        var rewinds = TranscriptRewind.of(records);
        for (var record : records) {
            if (!TranscriptKinds.STEER.equals(record.kind)) continue;
            if (!TranscriptKinds.STEER_PENDING.equals(record.text("state"))) continue;
            if (closed.contains(record.seq)) continue;
            if (rewinds.covers(record.turnId)) continue;
            var cancel = write(TranscriptKinds.STEER, record.turnId);
            cancel.put("state", TranscriptKinds.STEER_CANCELLED);
            cancel.put("ref_seq", record.seq);
        }
    }

    private long lastTerminalCount(int baseThrough) {
        long last = baseThrough;
        var rewinds = TranscriptRewind.of(allRecords());
        var compression = lastCompletedCompression();
        if (compression != null) {
            var count = compression.number("after_count");
            if (count != null && count > last) last = count;
        }
        for (var record : allRecords()) {
            if (compression != null && record.seq <= compression.seq) continue;
            if (!isTerminal(record) || rewinds.covers(record.turnId)) continue;
            var count = record.number("message_count");
            if (count != null && count > last) last = count;
        }
        return last;
    }

    private void reset(int baseThrough, int size) {
        var toSeq = firstInvalidTurn(size);
        if (toSeq == null) toSeq = nextSeq;
        var rewind = write(TranscriptKinds.REWIND, null);
        rewind.put("to_seq", toSeq);
        rewind.put("message_count", (long) size);
        writeReset(Math.min(baseThrough, size));
    }

    private void writeReset(int through) {
        var fingerprint = through > 0 ? TranscriptProjection.fingerprint(messages.subList(0, through)) : null;
        var record = write(TranscriptKinds.SESSION_START, null);
        record.put("workspace", metadata.workspace());
        record.put("engine_version", metadata.engineVersion());
        record.put("client", metadata.client());
        record.put("projection_reset", Boolean.TRUE);
        record.put("projection_through", (long) through);
        record.put("projection_fingerprint", fingerprint);
        store.setBaseRecords(TranscriptProjection.base(messages, through));
    }

    private Long firstInvalidTurn(int size) {
        var compression = lastCompletedCompression();
        if (compression != null && size < number(compression, "after_count", 0)) {
            var seq = TranscriptRewind.turnSeq(compression.turnId);
            if (seq >= 0) return seq;
        }
        Long first = null;
        for (var record : records) {
            if (compression != null && record.seq <= compression.seq) continue;
            if (!isTerminal(record)) continue;
            var count = record.number("message_count");
            if (count == null || count <= size) continue;
            var seq = TranscriptRewind.turnSeq(record.turnId);
            if (seq < 0) continue;
            if (first == null || seq < first) first = seq;
        }
        return first;
    }

    private TranscriptRecord lastCompletedCompression() {
        TranscriptRecord latest = null;
        for (var record : allRecords()) {
            if (!TranscriptKinds.COMPRESSION.equals(record.kind)) continue;
            if (!Boolean.TRUE.equals(record.bool("completed"))) continue;
            latest = record;
        }
        return latest;
    }

    private void gap(long from, long to) {
        if (from >= to) return;
        var fromIndex = (int) Math.min(from, messages.size());
        var toIndex = (int) Math.min(to, messages.size());
        if (fromIndex >= toIndex) return;
        writeSessionStart(fromIndex, null, (long) fromIndex);
        var startSeq = nextSeq;
        var gap = TranscriptProjection.gap(messages, fromIndex, toIndex, startSeq, store::writeBlob);
        append.addAll(gap);
        nextSeq = startSeq + gap.size();
    }

    private TranscriptUsage seed() {
        var rewinds = TranscriptRewind.of(allRecords());
        var usage = TranscriptUsage.EMPTY;
        for (var record : allRecords()) {
            if (!isTerminal(record) || rewinds.covers(record.turnId)) continue;
            usage = usage.add(usage(record));
        }
        return usage;
    }

    private TranscriptUsage usage(TranscriptRecord record) {
        return new TranscriptUsage(
            number(record, "input_tokens", 0),
            number(record, "output_tokens", 0),
            number(record, "cached_tokens", 0),
            decimal(record, "cost_usd", 0));
    }

    private boolean fingerprintMatches(String expected) {
        if (expected == null) return true;
        return expected.equals(TranscriptProjection.fingerprint(messages));
    }

    private List<TranscriptRecord> allRecords() {
        var all = new ArrayList<TranscriptRecord>(records);
        all.addAll(append);
        return all;
    }

    private static boolean isTerminal(TranscriptRecord record) {
        if (TranscriptKinds.TURN_COMPLETE.equals(record.kind)) return true;
        if (!TranscriptKinds.ERROR.equals(record.kind)) return false;
        return Boolean.TRUE.equals(record.bool("terminal"));
    }

    private static long number(TranscriptRecord record, String field, long fallback) {
        var value = record.number(field);
        return value == null ? fallback : value;
    }

    private static double decimal(TranscriptRecord record, String field, double fallback) {
        var value = record.decimal(field);
        return value == null ? fallback : value;
    }

    private TranscriptRecord write(String kind, String turnId) {
        var record = new TranscriptRecord();
        record.seq = nextSeq++;
        record.ts = System.currentTimeMillis();
        record.kind = kind;
        record.turnId = turnId;
        append.add(record);
        return record;
    }

}
