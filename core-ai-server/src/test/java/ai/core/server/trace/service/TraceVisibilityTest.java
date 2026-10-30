package ai.core.server.trace.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceVisibilityTest {
    @Test
    void unrestrictedPassesTheRequestThrough() {
        var filter = new TraceListFilter();
        filter.userId = "someone";

        TraceVisibility.all().applyTo(filter, "someone");

        assertEquals("someone", filter.userId);
        assertNull(filter.userIds);
    }

    @Test
    void scopedCallWithoutUserFilterUsesTheWholeSet() {
        var filter = new TraceListFilter();
        filter.userId = "ignored";

        TraceVisibility.of(Set.of("u1", "api:a")).applyTo(filter, null);

        assertNull(filter.userId);
        assertEquals(Set.copyOf(List.of("u1", "api:a")), Set.copyOf(filter.userIds));
    }

    @Test
    void requestedUserInsideTheSetNarrowsToThatUser() {
        var filter = new TraceListFilter();

        TraceVisibility.of(Set.of("u1", "api:a")).applyTo(filter, "api:a");

        assertEquals(List.of("api:a"), filter.userIds);
    }

    @Test
    void requestedUserOutsideTheSetMatchesNothing() {
        var filter = new TraceListFilter();

        TraceVisibility.of(Set.of("u1")).applyTo(filter, "u2");

        assertTrue(filter.userIds.isEmpty());
    }

    @Test
    void emptySetSeesNothing() {
        var visibility = TraceVisibility.of(Set.of());

        assertFalse(visibility.canSee("u1"));
        assertTrue(visibility.scopedUserIdsOrNull().isEmpty());
        assertNull(TraceVisibility.all().scopedUserIdsOrNull());
        assertTrue(TraceVisibility.all().canSee(null));
    }
}
