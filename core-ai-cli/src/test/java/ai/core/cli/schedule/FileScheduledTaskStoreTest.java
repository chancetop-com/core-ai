package ai.core.cli.schedule;

import ai.core.schedule.ScheduledTask;
import ai.core.schedule.ScheduledTaskRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class FileScheduledTaskStoreTest {

    @TempDir
    Path tempDir;

    private ScheduledTask newTask(String sessionId) {
        var task = new ScheduledTask();
        task.id = UUID.randomUUID().toString();
        task.sessionId = sessionId;
        task.userId = "user";
        task.name = "daily";
        task.cronExpression = "0 9 * * *";
        task.timezone = "UTC";
        task.input = "check status";
        task.enabled = Boolean.TRUE;
        return task;
    }

    @Test
    void createComputesNextRunAtAndPersists() throws Exception {
        var file = tempDir.resolve("schedules.json");
        var store = new FileScheduledTaskStore(file);
        var task = store.create(newTask("session-1"));

        assertNotNull(task.nextRunAt);
        assertNotNull(task.createdAt);
        assertTrue(Files.exists(file));

        // reload from disk
        var reloaded = new FileScheduledTaskStore(file);
        assertEquals(1, reloaded.list("session-1").size());
        assertEquals(task.id, reloaded.get(task.id).id);
    }

    @Test
    void findDueAndClaimAdvanceNextRunAt() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var task = store.create(newTask("session-1"));
        var now = ZonedDateTime.now();

        // task is not due yet (nextRunAt is tomorrow)
        assertTrue(store.findDue(now).isEmpty());

        // force due
        task.nextRunAt = now.minusMinutes(1);
        assertEquals(1, store.findDue(now).size());

        // claim advances next_run_at so the occurrence is consumed
        var claimedNext = now.plusHours(1);
        assertTrue(store.claim(task.id, now.minusMinutes(1), claimedNext));
        assertTrue(store.findDue(now).isEmpty());

        // claim with a stale expected value fails
        var stale = store.create(newTask("session-2"));
        stale.nextRunAt = now.minusMinutes(1);
        assertFalse(store.claim(stale.id, now.minusHours(1), now.plusHours(1)));
    }

    @Test
    void listFiltersBySession() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        store.create(newTask("session-1"));
        store.create(newTask("session-2"));

        assertEquals(1, store.list("session-1").size());
        assertEquals(1, store.list("session-2").size());
    }

    @Test
    void deleteRemovesTask() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var task = store.create(newTask("session-1"));

        assertTrue(store.delete(task.id));
        assertFalse(store.delete(task.id));
        assertNull(store.get(task.id));
        assertTrue(store.list("session-1").isEmpty());
    }

    @Test
    void disabledTasksAreNotDue() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var task = newTask("session-1");
        task.enabled = Boolean.FALSE;
        store.create(task);
        task.nextRunAt = ZonedDateTime.now().minusMinutes(1);

        assertTrue(store.findDue(ZonedDateTime.now()).isEmpty());
    }

    @Test
    void secondInstanceSeesWritesThroughReadThrough() {
        var file = tempDir.resolve("schedules.json");
        var first = new FileScheduledTaskStore(file);
        var second = new FileScheduledTaskStore(file);

        first.create(newTask("session-1"));

        assertEquals(1, second.list("session-1").size());
    }

    @Test
    void claimAcrossInstancesOnlyOneWins() {
        var file = tempDir.resolve("schedules.json");
        var first = new FileScheduledTaskStore(file);
        var second = new FileScheduledTaskStore(file);
        var task = first.create(newTask("session-1"));
        var expected = task.nextRunAt;

        assertTrue(first.claim(task.id, expected, ZonedDateTime.now().plusHours(1)));
        assertFalse(second.claim(task.id, expected, ZonedDateTime.now().plusHours(2)));
    }

    @Test
    void updateMergesFieldsAndRecomputesNextRunAtOnReenable() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var task = store.create(newTask("session-1"));

        var disable = new ScheduledTask();
        disable.id = task.id;
        disable.enabled = Boolean.FALSE;
        disable.disabledReason = "session_missing";
        var disabled = store.update(disable);
        assertFalse(disabled.enabled);
        assertEquals("session_missing", disabled.disabledReason);

        var enable = new ScheduledTask();
        enable.id = task.id;
        enable.enabled = Boolean.TRUE;
        var reEnabled = store.update(enable);
        assertTrue(reEnabled.enabled);
        assertNull(reEnabled.disabledReason);
        assertTrue(reEnabled.nextRunAt.isAfter(ZonedDateTime.now()));
        // definition fields survive the partial update
        assertEquals(task.name, reEnabled.name);
        assertEquals("0 9 * * *", reEnabled.cronExpression);
    }

    @Test
    void updateRejectsInvalidCronWithoutTouchingTheFile() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var task = store.create(newTask("session-1"));
        var patch = new ScheduledTask();
        patch.id = task.id;
        patch.cronExpression = "not a cron";

        assertThrows(IllegalArgumentException.class, () -> store.update(patch));
        assertEquals("0 9 * * *", store.get(task.id).cronExpression);
    }

    @Test
    void recordRunUpdatesLastRunWithoutBumpingUpdatedAtAndTruncatesHistory() {
        var file = tempDir.resolve("schedules.json");
        var store = new FileScheduledTaskStore(file);
        var task = store.create(newTask("session-1"));
        var updatedAt = task.updatedAt;

        for (int i = 0; i < 55; i++) {
            store.recordRun(newRun(task.id, i));
        }

        var stored = store.get(task.id);
        assertEquals(55, stored.runCount);
        assertEquals("fired", stored.lastRunStatus);
        assertEquals(updatedAt, stored.updatedAt);
        assertEquals(50, store.runs(task.id, 100).size());
        assertEquals("run-54", store.runs(task.id, 1).getFirst().id);
        // a second instance sees the same history and lastRun fields
        var reloaded = new FileScheduledTaskStore(file);
        assertEquals(55, reloaded.get(task.id).runCount);
        assertEquals(50, reloaded.runs(task.id, 100).size());
    }

    @Test
    void deleteRemovesRuns() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var task = store.create(newTask("session-1"));
        store.recordRun(newRun(task.id, 0));

        assertTrue(store.delete(task.id));
        assertTrue(store.runs(task.id, 10).isEmpty());
    }

    @Test
    void deleteBySessionRemovesTasksAndRuns() {
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"));
        var first = store.create(newTask("session-1"));
        store.create(newTask("session-2"));
        store.recordRun(newRun(first.id, 0));

        assertEquals(1, store.deleteBySession("session-1"));
        assertTrue(store.list("session-1").isEmpty());
        assertEquals(1, store.list("session-2").size());
        assertTrue(store.runs(first.id, 10).isEmpty());
    }

    @Test
    void workspaceIsStampedOnCreate() {
        var workspace = tempDir.resolve("ws");
        var store = new FileScheduledTaskStore(tempDir.resolve("schedules.json"), workspace);

        var task = store.create(newTask("session-1"));

        assertEquals(workspace.toAbsolutePath().normalize().toString(), task.workspace);
    }

    @Test
    void corruptFileDegradesToEmptyAndTheNextWriteRepairsIt() throws Exception {
        var file = tempDir.resolve("schedules.json");
        Files.writeString(file, "{ not json");
        var store = new FileScheduledTaskStore(file);

        assertTrue(store.list("session-1").isEmpty());

        store.create(newTask("session-1"));
        assertEquals(1, new FileScheduledTaskStore(file).list("session-1").size());
    }

    @Test
    void legacyFileWithoutNewFieldsIsReadable() throws Exception {
        var file = tempDir.resolve("schedules.json");
        Files.writeString(file, """
                [{"id":"legacy-1","sessionId":"session-1","userId":"user","name":"legacy",
                "cronExpression":"0 9 * * *","timezone":"UTC","input":"check","enabled":true,
                "nextRunAt":"2030-01-01T09:00:00+00:00","createdAt":"2026-01-01T09:00:00+00:00",
                "updatedAt":"2026-01-01T09:00:00+00:00"}]""");
        var store = new FileScheduledTaskStore(file);

        var task = store.get("legacy-1");
        assertNotNull(task);
        assertNull(task.workspace);
        assertNull(task.runCount);
        assertEquals(1, store.listAll().size());
    }

    private ScheduledTaskRun newRun(String taskId, int index) {
        var run = new ScheduledTaskRun();
        run.id = "run-" + index;
        run.taskId = taskId;
        run.sessionId = "session-1";
        run.firedAt = ZonedDateTime.now().plusSeconds(index);
        run.status = "fired";
        return run;
    }
}
