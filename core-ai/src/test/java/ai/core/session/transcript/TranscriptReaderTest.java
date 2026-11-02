package ai.core.session.transcript;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class TranscriptReaderTest {
    @TempDir
    Path dir;

    private static TranscriptRecord record(long seq, String kind, String turnId, Map<String, Object> payload) {
        var record = new TranscriptRecord();
        record.seq = seq;
        record.turnId = turnId;
        record.kind = kind;
        payload.forEach(record::put);
        return record;
    }

    private long appendTurn(TranscriptStore store, long seq, int turn, boolean complete) {
        var turnId = "t" + seq;
        store.append(record(seq++, TranscriptKinds.TURN_START, turnId, Map.of(
            "source", "user", "inputs", List.of(Map.of("text", "q" + turn)))));
        store.append(record(seq++, TranscriptKinds.ASSISTANT_TEXT, turnId, Map.of("text", "a" + turn)));
        if (complete) store.append(record(seq++, TranscriptKinds.TURN_COMPLETE, turnId, Map.of("message_count", 2)));
        return seq;
    }

    @Test
    void pagesWholeTurnsAndCursorPointsAtTheOldestRecord() {
        try (var store = TranscriptStore.open(dir, "s1")) {
            long seq = 1;
            seq = appendTurn(store, seq, 1, true);
            seq = appendTurn(store, seq, 2, true);
            appendTurn(store, seq, 3, false);
            var reader = new TranscriptReader(store);
            assertEquals(3, reader.totalTurns());

            var first = reader.page(store.maxSeq() + 1, 2, 1000);
            assertEquals(5, first.records().size());
            assertEquals("t4", first.records().getFirst().turnId);
            assertEquals(4L, first.nextBeforeSeq());
            assertEquals(List.of(new TranscriptReader.PartialTurn("t7", "tail")), first.partialTurns());
            assertEquals("transcript", first.source());

            var second = reader.page(first.nextBeforeSeq(), 2, 1000);
            assertEquals(3, second.records().size());
            assertEquals("t1", second.records().getFirst().turnId);
            assertNull(second.nextBeforeSeq());

            assertNull(reader.page(1, 2, 1000).nextBeforeSeq());
        }
    }

    @Test
    void marksTheHeadOfATurnCutByTheRecordBudget() {
        try (var store = TranscriptStore.open(dir, "s2")) {
            store.append(record(1, TranscriptKinds.TURN_START, "t1", Map.of("source", "user")));
            store.append(record(2, TranscriptKinds.ASSISTANT_TEXT, "t1", Map.of("text", "a")));
            store.append(record(3, TranscriptKinds.TOOL_START, "t1", Map.of("toolName", "grep")));
            store.append(record(4, TranscriptKinds.TOOL_RESULT, "t1", Map.of("status", "success")));
            store.append(record(5, TranscriptKinds.TURN_COMPLETE, "t1", Map.of("message_count", 2)));
            var reader = new TranscriptReader(store);

            var page = reader.page(store.maxSeq() + 1, 30, 2);
            assertEquals(List.of(4L, 5L), page.records().stream().map(entry -> entry.seq).toList());
            assertEquals(4L, page.nextBeforeSeq());
            assertEquals(List.of(new TranscriptReader.PartialTurn("t1", "head")), page.partialTurns());
        }
    }

    @Test
    void dropsTurnsDroppedByTheLatestRewind() {
        try (var store = TranscriptStore.open(dir, "s3")) {
            long seq = 1;
            seq = appendTurn(store, seq, 1, true);
            seq = appendTurn(store, seq, 2, true);
            seq = appendTurn(store, seq, 3, true);
            store.append(record(seq, TranscriptKinds.REWIND, null, Map.of("to_seq", 4)));
            var reader = new TranscriptReader(store);

            assertEquals(1, reader.totalTurns());
            var page = reader.page(store.maxSeq() + 1, 30, 1000);
            assertEquals(3, page.records().size());
            assertEquals("t1", page.records().getFirst().turnId);
            assertNull(page.nextBeforeSeq());
        }
    }

    @Test
    void reportsProjectionAndMixedSources() {
        try (var store = TranscriptStore.open(dir, "s4")) {
            store.setBaseRecords(List.of(
                record(-2, TranscriptKinds.TURN_START, "t-2", Map.of(
                    "source", "user", "inputs", List.of(Map.of("text", "old")))),
                record(-1, TranscriptKinds.ASSISTANT_TEXT, "t-2", Map.of("text", "old answer"))));
            var reader = new TranscriptReader(store);
            var projected = reader.page(store.maxSeq() + 1, 30, 1000);
            assertEquals("projection", projected.source());
            assertNull(projected.nextBeforeSeq());

            store.append(record(1, TranscriptKinds.TURN_START, "t1", Map.of(
                "source", "user", "inputs", List.of(Map.of("text", "new")))));
            var mixed = reader.page(store.maxSeq() + 1, 30, 1000);
            assertEquals("mixed", mixed.source());
            assertTrue(mixed.records().stream().anyMatch(entry -> entry.seq > 0));
        }
    }
}
