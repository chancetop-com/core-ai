package ai.core.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The queue cap is what keeps a session from absorbing unlimited input while a turn is running; the cap
 * itself comes from the configured session budgets, so a change applies to the next enqueue.
 *
 * @author stephen
 */
class SessionCommandQueueTest {
    @AfterEach
    void resetLimits() {
        SessionRuntimeLimits.configure(null);
    }

    @Test
    void refusesInputOnceTheQueueIsFull() {
        SessionRuntimeLimits.configure(() -> new SessionRuntimeLimits(2, 0));
        var queue = new SessionCommandQueue();

        assertTrue(queue.enqueueUserInput("a", null, null));
        assertTrue(queue.enqueueUserInput("b", null, null));
        assertFalse(queue.enqueueUserInput("c", null, null));
        assertEquals(2, queue.size());
    }

    @Test
    void taskNotificationsShareTheSameBudget() {
        SessionRuntimeLimits.configure(() -> new SessionRuntimeLimits(1, 0));
        var queue = new SessionCommandQueue();

        assertTrue(queue.enqueueTaskNotification("<task/>"));
        assertFalse(queue.enqueueUserInput("a", null, null));
    }

    @Test
    void staysUnlimitedUntilABudgetIsConfigured() {
        var queue = new SessionCommandQueue();
        for (int i = 0; i < 50; i++) {
            assertTrue(queue.enqueueUserInput("m" + i, null, null));
        }
        assertEquals(50, queue.size());

        SessionRuntimeLimits.configure(() -> new SessionRuntimeLimits(10, 0));
        assertFalse(queue.enqueueUserInput("late", null, null));
    }

    @Test
    void taskNotificationsDrainBeforeANewUserTask() {
        var queue = new SessionCommandQueue();
        queue.enqueueTaskNotification("<task/>");
        queue.enqueueUserInput("hi", null, null);

        var notifications = queue.drainSameMode();
        assertEquals(SessionCommandQueue.CommandMode.TASK_NOTIFICATION, notifications.mode());
        assertEquals(1, notifications.values().size());

        var user = queue.drainSameMode();
        assertEquals(SessionCommandQueue.CommandMode.USER_INPUT, user.mode());
        assertEquals(1, user.values().size());
    }

    @Test
    void keepsFifoOrderWithinTheSamePriority() {
        var queue = new SessionCommandQueue();
        for (var i = 0; i < 20; i++) {
            assertTrue(queue.enqueueUserInput("m" + i, null, null));
        }
        queue.enqueueTaskNotification("<task/>");

        var notifications = queue.drainSameMode();
        assertEquals(SessionCommandQueue.CommandMode.TASK_NOTIFICATION, notifications.mode());
        assertEquals(1, notifications.values().size());

        var batch = queue.drainSameMode();
        assertEquals(SessionCommandQueue.CommandMode.USER_INPUT, batch.mode());
        assertEquals(20, batch.values().size());
        for (var i = 0; i < 20; i++) {
            assertEquals("m" + i, batch.values().get(i).value());
        }
    }

    @Test
    void requeueReturnsAcceptedInputWithoutApplyingCapacityAgain() {
        SessionRuntimeLimits.configure(() -> new SessionRuntimeLimits(1, 0));
        var queue = new SessionCommandQueue();
        assertTrue(queue.enqueueUserInput("first", null, null, "turn-1"));
        var batch = queue.drainSameMode();
        assertTrue(queue.enqueueUserInput("filler", null, null));
        queue.requeueUserInputs(batch.values());
        assertEquals(2, queue.size());
    }
}
