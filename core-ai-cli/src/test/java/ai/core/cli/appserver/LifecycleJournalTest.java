package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author stephen
 */
class LifecycleJournalTest {
    private static com.fasterxml.jackson.databind.node.ObjectNode run(String taskRunId, long generation, String outcome) {
        var run = JsonUtil.OBJECT_MAPPER.createObjectNode();
        run.put("taskRunId", taskRunId);
        run.put("runGeneration", generation);
        run.put("state", outcome == null ? "running" : "terminal");
        if (outcome != null) run.put("outcome", outcome);
        return run;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode payload(com.fasterxml.jackson.databind.node.ObjectNode run) {
        var payload = JsonUtil.OBJECT_MAPPER.createObjectNode();
        payload.set("run", run);
        return payload;
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode endedPayload(com.fasterxml.jackson.databind.node.ObjectNode run,
                                                                              String outcome) {
        var payload = payload(run);
        var ended = JsonUtil.OBJECT_MAPPER.createObjectNode();
        ended.put("taskRunId", run.path("taskRunId").asText());
        ended.put("runGeneration", run.path("runGeneration").asLong());
        ended.put("outcome", outcome);
        payload.set("ended", ended);
        return payload;
    }

    @TempDir
    Path tempDir;

    @Test
    void appendsAndReloadsSeqAndIncarnation() {
        var journal = LifecycleJournal.open(tempDir, "session-1");
        var run = run("run-1", 1, null);
        journal.append("run_registered", payload(run));
        journal.append("run_state", payload(run));
        journal.append("run_ended", endedPayload(run, "returned"));
        var incarnation = journal.incarnation();
        var seq = journal.seq();
        journal.close();

        var reopened = LifecycleJournal.open(tempDir, "session-1");
        assertEquals(incarnation, reopened.incarnation());
        assertEquals(seq, reopened.seq());
        assertNotNull(reopened.lastEnded());
        assertNull(reopened.unfinishedRun());
        reopened.close();
    }

    @Test
    void detectsARunThatThePreviousEngineDidNotFinish() {
        var journal = LifecycleJournal.open(tempDir, "session-2");
        journal.append("run_registered", payload(run("run-2", 3, null)));
        journal.close();

        var reopened = LifecycleJournal.open(tempDir, "session-2");
        var unfinished = reopened.unfinishedRun();
        assertNotNull(unfinished);
        assertEquals("run-2", unfinished.path("taskRunId").asText());
        reopened.close();
    }

    @Test
    void replaysOnlyTheBoundRunAndHonorsTheSeq() {
        var journal = LifecycleJournal.open(tempDir, "session-3");
        var first = journal.append("run_registered", payload(run("run-3", 1, null)));
        journal.append("run_registered", payload(run("run-4", 1, null)));
        journal.append("run_state", payload(run("run-3", 1, null)));
        journal.append("run_ended", endedPayload(run("run-3", 1, null), "returned"));

        var records = journal.replayForRun("run-3", first.path("seq").asLong(), 10);
        assertEquals(2, records.size());
        assertEquals("run_state", records.get(0).path("type").asText());
        assertEquals("run_ended", records.get(1).path("type").asText());
        journal.close();
    }

    @Test
    void watchBindSurvivesReopenAndUnbindRemovesIt() {
        var journal = LifecycleJournal.open(tempDir, "session-4");
        var bind = JsonUtil.OBJECT_MAPPER.createObjectNode();
        bind.put("watchId", "watch-1");
        bind.put("taskRunId", "run-5");
        bind.put("runGeneration", 2);
        journal.append("watch_bind", bind);
        journal.close();

        var reopened = LifecycleJournal.open(tempDir, "session-4");
        assertNotNull(reopened.watchBind("watch-1"));
        var unbind = JsonUtil.OBJECT_MAPPER.createObjectNode();
        unbind.put("watchId", "watch-1");
        reopened.append("watch_unbind", unbind);
        assertNull(reopened.watchBind("watch-1"));
        reopened.close();
    }
}
