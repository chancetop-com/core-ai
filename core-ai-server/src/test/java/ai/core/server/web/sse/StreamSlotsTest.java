package ai.core.server.web.sse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamSlotsTest {
    @Test
    void acquireSucceedsUnderCap() {
        var slots = new StreamSlots(2);

        assertTrue(slots.acquire());
        assertTrue(slots.acquire());
    }

    @Test
    void acquireFailsOverCap() {
        var slots = new StreamSlots(1);
        assertTrue(slots.acquire());

        assertFalse(slots.acquire());
    }

    @Test
    void releaseFreesASlotForReuse() {
        var slots = new StreamSlots(1);
        assertTrue(slots.acquire());
        assertFalse(slots.acquire());

        slots.release();

        assertTrue(slots.acquire());
    }
}
