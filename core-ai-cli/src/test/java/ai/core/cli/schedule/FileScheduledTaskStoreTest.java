package ai.core.cli.schedule;

import ai.core.schedule.ScheduledTask;
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
}
