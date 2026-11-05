package ai.core.session.transcript;

import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class TranscriptRecorderTest {
    @TempDir
    Path dir;

    private TranscriptMetadata metadata() {
        return new TranscriptMetadata("ws", "test", "cli", () -> "model", () -> "low");
    }

    @Test
    void recordsATurnWithTextToolAndUsage() {
        var messages = new AtomicInteger();
        var records = new ArrayList<TranscriptRecord>();
        var recorder = new TranscriptRecorder(TranscriptStore.open(dir, "s1"), metadata(), messages::get, records::add);
        assertTrue(recorder.sessionStart(List.of()).isEmpty());
        recorder.beginTurn(List.of(new TranscriptInput("hi", List.of(), "client-1")), "user", "model", "low");
        recorder.onTextChunk(TextChunkEvent.of("s1", "hello "));
        recorder.onToolStart(ToolStartEvent.of("s1", "c1", "grep", "{\"q\":1}"));
        recorder.onToolResult(ToolResultEvent.of("s1", "c1", "grep", "success", "found"));
        recorder.onTextChunk(TextChunkEvent.of("s1", "done"));
        messages.set(4);
        var complete = TurnCompleteEvent.of("s1", "done", "client-1");
        complete.inputTokens = 10L;
        complete.outputTokens = 2L;
        complete.costUsd = 0.01;
        recorder.completeTurn(complete);
        recorder.close();

        var turnStart = records.stream().filter(record -> TranscriptKinds.TURN_START.equals(record.kind)).findFirst().orElseThrow();
        var turnComplete = records.stream().filter(record -> TranscriptKinds.TURN_COMPLETE.equals(record.kind)).findFirst().orElseThrow();
        assertEquals("t" + turnStart.seq, turnStart.turnId);
        assertEquals(turnStart.turnId, turnComplete.turnId);
        assertEquals(4L, turnComplete.number("message_count"));
        assertEquals(10L, turnComplete.number("input_tokens"));
        assertTrue(records.stream().anyMatch(record -> TranscriptKinds.TOOL_START.equals(record.kind)));
        assertTrue(records.stream().anyMatch(record -> TranscriptKinds.TOOL_RESULT.equals(record.kind)));
        assertEquals(2, records.stream().filter(record -> TranscriptKinds.ASSISTANT_TEXT.equals(record.kind)).count());

        var reopened = TranscriptStore.open(dir, "s1");
        var page = new TranscriptReader(reopened).page(reopened.maxSeq() + 1, 30, 1000);
        assertEquals(1, page.totalTurns());
        assertEquals("transcript", page.source());
        assertTrue(page.records().stream().anyMatch(record -> TranscriptKinds.TURN_COMPLETE.equals(record.kind)));
        reopened.close();
    }

    @Test
    void projectsLegacyMessagesAndACompressionSummary() {
        var messages = List.of(
            Message.of(RoleType.USER, "old question"),
            Message.of(RoleType.ASSISTANT, "old answer"),
            Message.of(RoleType.ASSISTANT, null, null, null,
                List.of(FunctionCall.of("c1", "function", "memory_compress", "{}"))),
            Message.of(RoleType.TOOL, "summary", "memory_compress", "c1", null));
        var base = TranscriptProjection.base(messages, messages.size());
        for (var i = 0; i < base.size(); i++) {
            assertEquals(-(base.size() - i), base.get(i).seq);
            assertNotNull(base.get(i).turnId);
        }
        assertTrue(base.stream().noneMatch(record -> TranscriptKinds.TOOL_START.equals(record.kind)));
        assertTrue(base.stream().anyMatch(record -> TranscriptKinds.NOTICE.equals(record.kind)
            && "compression".equals(record.text("level"))));
    }

    @Test
    void classifiesTaskNotificationAndSystemReminder() {
        assertTrue(TranscriptProjection.isTaskNotification("<task-notification><task-id>x</task-id></task-notification>"));
        assertTrue(TranscriptProjection.isSystemReminder("<system-reminder>The user interrupted</system-reminder>"));
        var messages = List.of(
            Message.of(RoleType.USER, "<task-notification><task-id>x</task-id><status>completed</status></task-notification>"),
            Message.of(RoleType.ASSISTANT, "task done"));
        var records = TranscriptProjection.gap(messages, 0, messages.size(), 5, null);
        var start = records.stream().filter(record -> TranscriptKinds.TURN_START.equals(record.kind)).findFirst().orElseThrow();
        assertEquals("t5", start.turnId);
        assertEquals("task_note", start.text("source"));
        assertTrue(records.stream().anyMatch(record -> TranscriptKinds.TURN_COMPLETE.equals(record.kind)));
        assertTrue(records.stream().allMatch(record -> Boolean.TRUE.equals(record.synthetic)));
    }

    @Test
    void rewindsTheLastTurnAndDropsItFromTheTotal() {
        var messages = new AtomicInteger();
        var recorder = new TranscriptRecorder(TranscriptStore.open(dir, "s2"), metadata(), messages::get, record -> {
        });
        recorder.sessionStart(List.of());
        recorder.beginTurn(List.of(new TranscriptInput("hi", List.of(), null)), "user", "model", "low");
        messages.set(2);
        recorder.completeTurn(TurnCompleteEvent.of("s2", "done"));
        var usage = recorder.rewindLast();
        assertNotNull(usage);
        var store = recorder.store();
        assertEquals(0, new TranscriptReader(store).totalTurns());
        assertTrue(store.records().stream().anyMatch(record -> TranscriptKinds.REWIND.equals(record.kind)));
        recorder.close();
    }

    @Test
    void doesNotReplayTurnsAppendedAfterARewindOnStartup() {
        var messageCount = new AtomicInteger();
        var store = TranscriptStore.open(dir, "s2b");
        var recorder = new TranscriptRecorder(store, metadata(), messageCount::get, record -> {
        });
        recorder.sessionStart(List.of());
        recorder.beginTurn(List.of(new TranscriptInput("one", List.of(), null)), "user", "model", "low");
        messageCount.set(2);
        recorder.completeTurn(TurnCompleteEvent.of("s2b", "one"));
        recorder.beginTurn(List.of(new TranscriptInput("two", List.of(), null)), "user", "model", "low");
        messageCount.set(4);
        recorder.completeTurn(TurnCompleteEvent.of("s2b", "two"));
        recorder.rewindLast();
        recorder.beginTurn(List.of(new TranscriptInput("three", List.of(), null)), "user", "model", "low");
        messageCount.set(6);
        recorder.completeTurn(TurnCompleteEvent.of("s2b", "three"));
        recorder.close();

        var messages = new ArrayList<Message>();
        for (int i = 0; i < 6; i++) messages.add(Message.of(RoleType.USER, "m" + i));
        var reopened = TranscriptStore.open(dir, "s2b");
        var result = new TranscriptStartup(reopened, metadata(), messages).start();
        assertTrue(result.append().stream().noneMatch(record -> TranscriptKinds.REWIND.equals(record.kind)));
        assertTrue(result.append().stream().noneMatch(record -> Boolean.TRUE.equals(record.synthetic)));

        for (var record : result.append()) reopened.append(record);
        assertEquals(2, new TranscriptReader(reopened).totalTurns());
        reopened.close();
    }

    @Test
    void recordsTerminalErrorAndUsesItsMessageCountForTheNextStart() {
        var messages = new AtomicInteger();
        var recorder = new TranscriptRecorder(TranscriptStore.open(dir, "s3"), metadata(), messages::get, record -> {
        });
        recorder.sessionStart(List.of());
        recorder.beginTurn(List.of(new TranscriptInput("hi", List.of(), null)), "user", "model", "low");
        messages.set(3);
        var error = ErrorEvent.of("s3", "boom", "detail");
        recorder.failTurn(error, new TranscriptUsage(7, 1, 0, 0.02));
        recorder.close();
        var store = TranscriptStore.open(dir, "s3");
        var startup = new TranscriptStartup(store, metadata(), List.of(
            Message.of(RoleType.USER, "hi"),
            Message.of(RoleType.ASSISTANT, "partial"),
            Message.of(RoleType.ASSISTANT, "extra")));
        var result = startup.start();
        assertFalse(result.append().stream().anyMatch(record -> record.synthetic != null));
        assertEquals(7, result.seed().inputTokens());
        store.close();
    }

    @Test
    void keepsTurnsWhenACompletedCompressionShortenedTheConversation() {
        var messageCount = new AtomicInteger();
        var store = TranscriptStore.open(dir, "s10");
        var recorder = new TranscriptRecorder(store, metadata(), messageCount::get, record -> {
        });
        recorder.sessionStart(List.of());
        recorder.beginTurn(List.of(new TranscriptInput("one", List.of(), null)), "user", "model", "low");
        messageCount.set(80);
        recorder.completeTurn(TurnCompleteEvent.of("s10", "one"));
        recorder.beginTurn(List.of(new TranscriptInput("two", List.of(), null)), "user", "model", "low");
        recorder.onCompression(CompressionEvent.of("s10", 80, 8, true));
        messageCount.set(10);
        recorder.completeTurn(TurnCompleteEvent.of("s10", "two"));
        recorder.close();

        var messages = new ArrayList<Message>();
        for (int i = 0; i < 10; i++) messages.add(Message.of(RoleType.USER, "m" + i));
        var reopened = TranscriptStore.open(dir, "s10");
        var result = new TranscriptStartup(reopened, metadata(), messages).start();
        assertTrue(result.append().stream().noneMatch(record -> TranscriptKinds.REWIND.equals(record.kind)));
        assertTrue(result.append().stream().noneMatch(record -> Boolean.TRUE.equals(record.bool("projection_reset"))));

        for (var record : result.append()) reopened.append(record);
        assertEquals(2, new TranscriptReader(reopened).totalTurns());
        reopened.close();
    }

    @Test
    void recordsSteerOrderAndClientTurnIds() {
        var store = TranscriptStore.open(dir, "s5");
        var recorder = new TranscriptRecorder(store, metadata(), () -> 0, record -> {
        });
        recorder.sessionStart(List.of());
        recorder.beginTurn(List.of(new TranscriptInput("hi", List.of(), "client-1")), "user", "model", "low");
        var steerSeq = recorder.steerPending(new TranscriptInput("more", List.of(), "client-2"));
        recorder.steerCommit(steerSeq);
        recorder.completeTurn(TurnCompleteEvent.of("s5", "done"));
        recorder.close();

        var records = store.records();
        var turnStart = records.stream()
            .filter(record -> TranscriptKinds.TURN_START.equals(record.kind)).findFirst().orElseThrow();
        var inputs = turnStart.objects("inputs");
        assertEquals("client-1", inputs.getFirst().get("client_turn_id"));
        var steers = records.stream().filter(record -> TranscriptKinds.STEER.equals(record.kind)).toList();
        assertEquals(List.of(TranscriptKinds.STEER_PENDING, TranscriptKinds.STEER_COMMITTED),
            steers.stream().map(record -> record.text("state")).toList());
        assertEquals(steerSeq, steers.get(1).number("ref_seq"));
        assertEquals("client-2", steers.getFirst().text("client_turn_id"));
        assertEquals(steerSeq, steers.getFirst().seq);
    }

    @Test
    void cancelsADanglingSteerOnStartupAndRecoversTheOpenTurn() {
        var store = TranscriptStore.open(dir, "s6");
        var messages = new AtomicInteger(2);
        var recorder = new TranscriptRecorder(store, metadata(), messages::get, record -> {
        });
        recorder.sessionStart(List.of());
        recorder.beginTurn(List.of(new TranscriptInput("hi", List.of(), null)), "user", "model", "low");
        recorder.steerPending(new TranscriptInput("more", List.of(), null));
        // A crash: the store closes without the recorder's graceful turn_complete.
        store.close();

        var reopened = TranscriptStore.open(dir, "s6");
        var startup = new TranscriptStartup(reopened, metadata(), List.of(
            Message.of(RoleType.USER, "hi"), Message.of(RoleType.ASSISTANT, "partial")));
        var result = startup.start();
        assertTrue(result.append().stream().anyMatch(record -> TranscriptKinds.STEER.equals(record.kind)
            && TranscriptKinds.STEER_CANCELLED.equals(record.text("state"))));
        assertTrue(result.append().stream().anyMatch(record -> TranscriptKinds.TURN_COMPLETE.equals(record.kind)
            && Boolean.TRUE.equals(record.bool("cancelled"))));
        reopened.close();
    }

    @Test
    void deletesTranscriptAndBlobsTogether() {
        var store = TranscriptStore.open(dir, "s7");
        store.append(new TranscriptRecord());
        store.writeBlob(1, "result", 0, "payload");
        store.close();
        assertTrue(Files.exists(dir.resolve("s7.events.jsonl")));
        assertTrue(Files.exists(dir.resolve("s7.blobs")));

        TranscriptStore.delete(dir, "s7");
        assertFalse(Files.exists(dir.resolve("s7.events.jsonl")));
        assertFalse(Files.exists(dir.resolve("s7.blobs")));
    }

    @Test
    void seedSumsLiveTurnsOnly() {
        var messages = new AtomicInteger();
        var store = TranscriptStore.open(dir, "s8");
        var recorder = new TranscriptRecorder(store, metadata(), messages::get, record -> {
        });
        recorder.sessionStart(List.of());
        recorder.beginTurn(List.of(new TranscriptInput("one", List.of(), null)), "user", "model", "low");
        messages.set(3);
        var firstTurn = TurnCompleteEvent.of("s8", "one");
        firstTurn.inputTokens = 10L;
        firstTurn.outputTokens = 2L;
        firstTurn.cachedTokens = 1L;
        firstTurn.costUsd = 0.01;
        recorder.completeTurn(firstTurn);
        recorder.beginTurn(List.of(new TranscriptInput("two", List.of(), null)), "user", "model", "low");
        messages.set(6);
        var secondTurn = TurnCompleteEvent.of("s8", "two");
        secondTurn.inputTokens = 20L;
        secondTurn.outputTokens = 4L;
        secondTurn.costUsd = 0.02;
        recorder.completeTurn(secondTurn);
        recorder.close();

        var reopened = TranscriptStore.open(dir, "s8");
        var startup = new TranscriptStartup(reopened, metadata(), List.of(
            Message.of(RoleType.USER, "one"), Message.of(RoleType.ASSISTANT, "one"),
            Message.of(RoleType.USER, "two"), Message.of(RoleType.ASSISTANT, "two"),
            Message.of(RoleType.ASSISTANT, "more"), Message.of(RoleType.ASSISTANT, "end")));
        var result = startup.start();
        assertEquals(30, result.seed().inputTokens());
        assertEquals(6, result.seed().outputTokens());
        assertEquals(1, result.seed().cachedTokens());
        assertEquals(0.03, result.seed().costUsd(), 0.0001);
        reopened.close();
    }

    @Test
    void dropsATruncatedTailLineBeforeAppendingAgain() throws Exception {
        var store = TranscriptStore.open(dir, "s9");
        var first = new TranscriptRecord();
        first.seq = 1;
        first.kind = TranscriptKinds.SESSION_START;
        store.append(first);
        store.close();

        Files.writeString(dir.resolve("s9.events.jsonl"), "{\"v\":1,\"seq\":2,", StandardOpenOption.APPEND);
        var reopened = TranscriptStore.open(dir, "s9");
        assertEquals(1, reopened.records().size());
        var next = new TranscriptRecord();
        next.seq = 3;
        next.kind = TranscriptKinds.NOTICE;
        next.put("text", "ok");
        reopened.append(next);
        reopened.close();

        var again = TranscriptStore.open(dir, "s9");
        assertEquals(2, again.records().size());
        assertEquals(TranscriptKinds.NOTICE, again.records().get(1).kind);
        again.close();
    }

    @Test
    void storesAndBoundsBlobPayloads() {
        var store = TranscriptStore.open(dir, "s4");
        var ref = store.writeBlob(1, "result", 0, "payload");
        assertEquals("payload", store.readBlob(ref));
        assertNotNull(store.blobPath(ref));
        assertNull(store.blobPath("../escape"));
        var large = "x".repeat(40 * 1024);
        var record = new TranscriptRecord();
        record.seq = 2;
        TranscriptPayloads.putText(store, record, "result", large);
        assertNotNull(record.text("result_ref"));
        assertEquals(4 * 1024, record.text("result_preview").length());
        assertNull(record.text("result"));
        store.close();
        assertTrue(Files.exists(dir.resolve("s4.events.jsonl")));
    }
}
