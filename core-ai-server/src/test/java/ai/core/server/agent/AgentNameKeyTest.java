package ai.core.server.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentNameKeyTest {
    @Test
    void normalizesWithRootLocaleAndTrim() {
        assertEquals("review assistant", AgentNameKey.normalize("  Review Assistant  "));
        assertEquals("i", AgentNameKey.normalize("I"));
        assertEquals("", AgentNameKey.normalize(null));
    }
}
