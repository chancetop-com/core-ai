package ai.core.server.workflow;

import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Confirms an Agent published config — including the nested tools / sandbox / dataset config the child agent
 * actually executes with — survives the JSON round-trip used to embed it in a workflow published version
 * (publish stores {@code JSON.toJSON(config)}; the gateway reads it back with {@code JSON.fromJSON}). If any
 * nested field were silently dropped, the child agent would run stripped of its tools/sandbox/datasets, which
 * is exactly the anti-drift snapshot's job to prevent.
 *
 * @author Xander
 */
class AgentSnapshotRoundTripTest {
    private static ToolRef toolRef(String id, ToolSourceType type, String source) {
        var ref = new ToolRef();
        ref.id = id;
        ref.type = type;
        ref.source = source;
        return ref;
    }

    private static AgentSandboxConfig sandbox() {
        var config = new AgentSandboxConfig();
        config.enabled = true;
        config.memoryLimitMb = 512;
        config.networkEnabled = false;
        config.environmentVariables = Map.of("API_KEY", "secret-value");
        return config;
    }

    private static AgentDatasetConfig dataset(String datasetId, boolean isOutput) {
        var config = new AgentDatasetConfig();
        config.datasetId = datasetId;
        config.isOutput = isOutput;
        return config;
    }

    @Test
    void agentPublishedConfigRoundTripsIncludingNestedFields() {
        var config = new AgentPublishedConfig();
        config.systemPrompt = "you are a ticket classifier";
        config.model = "claude-opus-4-8";
        config.temperature = 0.3;
        config.maxTurns = 5;
        config.timeoutSeconds = 120;
        config.responseSchema = "{\"type\":\"object\"}";
        config.variables = Map.of("locale", "en");
        config.skillIds = List.of("skill-a", "skill-b");
        config.subAgentIds = List.of("sub-1");
        config.tools = List.of(toolRef("builtin-all", ToolSourceType.BUILTIN, null), toolRef("create_issue", ToolSourceType.MCP, "jira"));
        config.sandboxConfig = sandbox();
        config.datasetConfig = List.of(dataset("ds-1", true));

        AgentPublishedConfig back = JSON.fromJSON(AgentPublishedConfig.class, JSON.toJSON(config));

        assertEquals("you are a ticket classifier", back.systemPrompt);
        assertEquals("claude-opus-4-8", back.model);
        assertEquals(0.3, back.temperature);
        assertEquals(5, back.maxTurns);
        assertEquals(120, back.timeoutSeconds);
        assertEquals(Map.of("locale", "en"), back.variables);
        assertEquals(List.of("skill-a", "skill-b"), back.skillIds);
        assertEquals(List.of("sub-1"), back.subAgentIds);

        assertEquals(2, back.tools.size());
        assertEquals(ToolSourceType.BUILTIN, back.tools.get(0).type);
        assertEquals("create_issue", back.tools.get(1).id);
        assertEquals("jira", back.tools.get(1).source);

        assertEquals(Boolean.TRUE, back.sandboxConfig.enabled);
        assertEquals(512, back.sandboxConfig.memoryLimitMb);
        assertEquals(Map.of("API_KEY", "secret-value"), back.sandboxConfig.environmentVariables);

        assertEquals(1, back.datasetConfig.size());
        assertEquals("ds-1", back.datasetConfig.get(0).datasetId);
        assertEquals(Boolean.TRUE, back.datasetConfig.get(0).isOutput);
    }
}
