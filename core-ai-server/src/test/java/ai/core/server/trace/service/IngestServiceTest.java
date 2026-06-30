package ai.core.server.trace.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IngestServiceTest {
    @Test
    void resolveUserIdPrefersAuthenticatedOverAttribute() {
        // Authenticated userId from Bearer always wins over client-supplied attribute (route B).
        assertEquals("auth-user", IngestService.resolveUserId("auth-user", Map.of("user.id", "spoofed")));
    }

    @Test
    void resolveUserIdFallsBackToAttributeWhenUnauthenticated() {
        // Legacy anonymous path (authUserId == null) keeps the old attribute behavior.
        assertEquals("attr-user", IngestService.resolveUserId(null, Map.of("user.id", "attr-user")));
    }

    @Test
    void resolveUserIdNullWhenNeitherPresent() {
        assertNull(IngestService.resolveUserId(null, null));
    }

    @Test
    void resolveUserIdFallsBackWhenAuthUserIdIsBlank() {
        // A blank Bearer-resolved id must not be written as the user; fall back to the attribute.
        assertEquals("attr-user", IngestService.resolveUserId("  ", Map.of("user.id", "attr-user")));
    }

    @Test
    void friendlyTraceNameUsesAgentNameForOperationSpan() {
        assertEquals("Support Summarizer", IngestService.friendlyTraceName("agent.run", "Support Summarizer"));
    }

    @Test
    void friendlyTraceNameKeepsSpecificSpanName() {
        assertEquals("classify_ticket", IngestService.friendlyTraceName("classify_ticket", "Support Summarizer"));
    }
}
