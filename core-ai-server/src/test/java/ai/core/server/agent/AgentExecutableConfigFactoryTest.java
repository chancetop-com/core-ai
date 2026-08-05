package ai.core.server.agent;

import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutableConfigFactoryTest {
    @Test
    void editableFactoryCopiesEveryExecutableFieldWithoutAliasing() {
        var source = fullyConfiguredEditableDefinition();
        AgentPublishedConfig config = AgentExecutableConfigFactory.fromEditableDefinition(source);

        mutateEditableDefinition(source);

        assertEditableCopy(source, config);
    }

    private AgentDefinition fullyConfiguredEditableDefinition() {
        var sandbox = new AgentSandboxConfig();
        sandbox.environmentVariables = new HashMap<>(Map.of("TOKEN", "original"));
        var dataset = new AgentDatasetConfig();
        dataset.datasetId = "dataset-1";
        dataset.isOutput = Boolean.FALSE;
        var tool = ToolRef.of("builtin-web", ToolSourceType.BUILTIN);
        var source = new AgentDefinition();
        source.systemPrompt = "review changes";
        source.systemPromptId = "prompt-1";
        source.model = "model-1";
        source.multiModalModel = "vision-1";
        source.preferCaptionPath = Boolean.TRUE;
        source.temperature = 0.2;
        source.thinkingEffort = "high";
        source.maxTurns = 12;
        source.timeoutSeconds = 300;
        source.tools = new ArrayList<>(List.of(tool));
        source.skillIds = new ArrayList<>(Arrays.asList(" skill-1 ", "skill-1", " ", null));
        source.subAgentIds = new ArrayList<>(Arrays.asList(" sub-1 ", "sub-1", " ", null));
        source.inputTemplate = "{{ input }}";
        source.variables = new HashMap<>(Map.of("locale", "en"));
        source.responseSchema = "{\"type\":\"object\"}";
        source.enableMemory = Boolean.FALSE;
        source.sandboxConfig = sandbox;
        source.datasetConfig = new ArrayList<>(List.of(dataset));
        return source;
    }

    private void mutateEditableDefinition(AgentDefinition source) {
        source.tools.getFirst().id = "changed-tool";
        source.variables.put("locale", "changed");
        source.sandboxConfig.environmentVariables.put("TOKEN", "changed");
        source.datasetConfig.getFirst().isOutput = Boolean.TRUE;
    }

    private void assertEditableCopy(AgentDefinition source, AgentPublishedConfig config) {
        assertEquals("review changes", config.systemPrompt);
        assertEquals("prompt-1", config.systemPromptId);
        assertEquals("model-1", config.model);
        assertEquals("vision-1", config.multiModalModel);
        assertEquals(Boolean.TRUE, config.preferCaptionPath);
        assertEquals(0.2, config.temperature);
        assertEquals("high", config.thinkingEffort);
        assertEquals(12, config.maxTurns);
        assertEquals(300, config.timeoutSeconds);
        assertEquals("builtin-web", config.tools.getFirst().id);
        assertEquals(List.of("skill-1"), config.skillIds);
        assertEquals(List.of("sub-1"), config.subAgentIds);
        assertEquals("{{ input }}", config.inputTemplate);
        assertEquals("en", config.variables.get("locale"));
        assertEquals("{\"type\":\"object\"}", config.responseSchema);
        assertEquals(Boolean.FALSE, config.enableMemory);
        assertEquals("original", config.sandboxConfig.environmentVariables.get("TOKEN"));
        assertNotSame(source.datasetConfig, config.datasetConfig);
        assertEquals("dataset-1", config.datasetConfig.getFirst().datasetId);
        assertEquals(Boolean.FALSE, config.datasetConfig.getFirst().isOutput);
    }

    @Test
    void publishedFactoryReturnsADeepCopy() {
        var source = AgentExecutableConfigFactory.fromEditableDefinition(editableDefinition());
        var copy = AgentExecutableConfigFactory.fromPublishedConfig(source);
        var originalJson = JSON.toJSON(source);

        source.variables.put("locale", "changed");
        source.sandboxConfig.environmentVariables.put("TOKEN", "changed");
        source.datasetConfig.getFirst().isOutput = Boolean.TRUE;

        assertNotSame(source, copy);
        assertEquals(originalJson, JSON.toJSON(copy));
        assertEquals("en", copy.variables.get("locale"));
        assertEquals("original", copy.sandboxConfig.environmentVariables.get("TOKEN"));
        assertEquals(Boolean.FALSE, copy.datasetConfig.getFirst().isOutput);
    }

    @Test
    void publishedFactoryRejectsMissingConfig() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> AgentExecutableConfigFactory.fromPublishedConfig(null));

        assertEquals("published agent config is missing", exception.getMessage());
    }

    private AgentDefinition editableDefinition() {
        var source = new AgentDefinition();
        source.variables = new HashMap<>(Map.of("locale", "en"));
        source.sandboxConfig = new AgentSandboxConfig();
        source.sandboxConfig.environmentVariables = new HashMap<>(Map.of("TOKEN", "original"));
        var dataset = new AgentDatasetConfig();
        dataset.datasetId = "dataset-1";
        dataset.isOutput = Boolean.FALSE;
        source.datasetConfig = new ArrayList<>(List.of(dataset));
        return source;
    }
}
