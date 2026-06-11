package ai.core.server.run;

import ai.core.server.domain.AgentDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRunnerTest {
    @Test
    void safeNodeNameSanitizesDisplayNamesToToolSafeForm() {
        assertEquals("Xander-test-(1)", name("Xander-test (1)"));
        assertEquals("a-b-c", name("a <b>/c"));
        assertEquals("plain-name", name("plain-name"));
    }

    @Test
    void safeNodeNameFallsBackToIdWhenNameMissing() {
        assertEquals("agent-id-1", name(null));
        assertEquals("agent-id-1", name("   "));
    }

    private String name(String displayName) {
        var definition = new AgentDefinition();
        definition.id = "id-1";
        definition.name = displayName;
        return AgentRunner.safeNodeName(definition);
    }
}
