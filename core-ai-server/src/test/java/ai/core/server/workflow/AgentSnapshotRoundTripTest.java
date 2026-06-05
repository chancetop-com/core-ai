package ai.core.server.workflow;

import ai.core.server.domain.AgentPublishedConfig;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Confirms an Agent published config survives the JSON round-trip used to embed it in a workflow published
 * version (publish stores {@code JSON.toJSON(config)}; the gateway reads it back with {@code JSON.fromJSON}).
 */
class AgentSnapshotRoundTripTest {
    @Test
    void agentPublishedConfigRoundTripsThroughJson() {
        var config = new AgentPublishedConfig();
        config.systemPrompt = "you are a ticket classifier";
        config.model = "claude-opus-4-8";
        config.temperature = 0.3;
        config.maxTurns = 5;
        config.timeoutSeconds = 120;
        config.responseSchema = "{\"type\":\"object\"}";

        String json = JSON.toJSON(config);
        AgentPublishedConfig back = JSON.fromJSON(AgentPublishedConfig.class, json);

        assertEquals("you are a ticket classifier", back.systemPrompt);
        assertEquals("claude-opus-4-8", back.model);
        assertEquals(0.3, back.temperature);
        assertEquals(5, back.maxTurns);
        assertEquals(120, back.timeoutSeconds);
        assertEquals("{\"type\":\"object\"}", back.responseSchema);
    }
}
