package ai.core.server.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionIdsTest {
    @Test
    void derivedIdsCarryTheServerPrefix() {
        var sessionId = SessionIds.derivedFrom("0123456789abcdef0123456789abcdef");

        assertEquals("fp1-0123456789abcdef0123456789abcdef", sessionId);
        assertTrue(SessionIds.isDerived(sessionId));
    }

    @Test
    void clientSuppliedSessionIdsAreNotMarkedDerived() {
        assertFalse(SessionIds.isDerived("01a0b1c2-d3e4-4f5a-8b7c-9d0e1f2a3b4c"));
        assertFalse(SessionIds.isDerived("codex-session-1"));
        assertFalse(SessionIds.isDerived(null));
        assertFalse(SessionIds.isDerived(""));
    }
}
