package ai.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class PendingUserInputQueueTest {

    @Test
    void drainsInFifoOrder() {
        var queue = new PendingUserInputQueue(0);
        assertTrue(queue.offer("first", null, null));
        assertTrue(queue.offer("second", Map.of("key", "value"), null));

        var drained = queue.drain();
        assertEquals(2, drained.size());
        assertEquals("first", drained.get(0).message());
        assertEquals("second", drained.get(1).message());
        assertEquals("value", drained.get(1).variables().get("key"));
        assertTrue(queue.isEmpty());
    }

    @Test
    void refusesInputAboveTheLimit() {
        var queue = new PendingUserInputQueue(1);
        assertTrue(queue.offer("first", null, null));
        assertFalse(queue.offer("second", null, null));
        assertEquals(1, queue.size());
    }

    @Test
    void closeRejectsFurtherInputAndReturnsTheRemainder() {
        var queue = new PendingUserInputQueue(0);
        assertTrue(queue.offer("already accepted", null, null));

        var drained = queue.closeAndDrain();
        assertEquals(1, drained.size());
        assertFalse(queue.offer("too late", null, null));
        assertTrue(queue.drain().isEmpty());
    }
}
