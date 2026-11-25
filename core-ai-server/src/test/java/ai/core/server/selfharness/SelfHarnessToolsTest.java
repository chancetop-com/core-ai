package ai.core.server.selfharness;

import ai.core.api.jsonschema.JsonSchema.PropertyType;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.domain.migration.SchemaMigrationVDefaultAgent;
import ai.core.tool.ToolCall;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The agent builder only sees tools that are registered in the self-harness group and referenced
 * on the agent, so the dataset report tools must be part of both.
 */
class SelfHarnessToolsTest {
    @Test
    void updateSkillResourceSchemaAcceptsKeptFilesAndTextReplacements() {
        var registry = initializedRegistry();
        ArgumentCaptor<List<ToolCall>> registeredTools = ArgumentCaptor.captor();
        verify(registry).registerBuiltinToolGroup(anyString(), anyString(), anyString(), registeredTools.capture(), anyMap());
        var update = registeredTools.getValue().stream().filter(tool -> "update_skill".equals(tool.getName())).findFirst().orElseThrow();

        var parameters = update.toTool().function.parameters;
        assertEquals(List.of("id"), parameters.required);
        assertEquals(PropertyType.BOOLEAN, parameters.properties.get("check_only").type);
        var resources = parameters.properties.get("resources");
        assertEquals(PropertyType.ARRAY, resources.type);
        assertEquals(Set.of("path", "keep", "content"), resources.items.properties.keySet());
        assertEquals(PropertyType.STRING, resources.items.properties.get("path").type);
        assertEquals(PropertyType.BOOLEAN, resources.items.properties.get("keep").type);
        assertEquals(PropertyType.STRING, resources.items.properties.get("content").type);
        assertEquals(List.of("path"), resources.items.required);
        assertTrue(update.getDescription().contains("SKILL_METADATA_MISMATCH"));

        var create = registeredTools.getValue().stream().filter(tool -> "create_skill".equals(tool.getName())).findFirst().orElseThrow();
        var createResources = create.toTool().function.parameters.properties.get("resources");
        assertEquals(PropertyType.ARRAY, createResources.type);
        assertEquals(Set.of("path", "content"), createResources.items.properties.keySet());
        assertEquals(List.of("path", "content"), createResources.items.required);
        assertTrue(create.getDescription().contains("eval/eval.json"));
    }

    @Test
    void datasetReportToolsAreRegisteredInTheDatasetGroup() {
        var registry = initializedRegistry();

        verify(registry).registerBuiltinToolGroup(anyString(), anyString(), anyString(),
                argThat(tools -> tools.stream().map(ToolCall::getName).toList().containsAll(SchemaMigrationVDefaultAgent.AGENT_BUILDER_TOOL_NAMES)),
                argThat(groups -> List.of("list_report_sources", "list_dataset_reports", "get_dataset_report", "preview_dataset_report",
                        "create_dataset_report", "update_dataset_report").stream().allMatch(name -> "Datasets".equals(groups.get(name)))));
    }

    @Test
    void reportSpecGuideIsExposedOnTheCreateTool() {
        var registry = initializedRegistry();

        verify(registry).registerBuiltinToolGroup(anyString(), anyString(), anyString(),
                argThat(tools -> tools.stream()
                        .filter(tool -> "create_dataset_report".equals(tool.getName()))
                        .anyMatch(tool -> tool.getDescription().contains("spec_version=1")
                                && tool.getDescription().contains("display.type="))),
                anyMap());
    }

    private ToolRegistryService initializedRegistry() {
        var registry = mock(ToolRegistryService.class);
        var tools = new SelfHarnessTools();
        tools.caller = mock(SelfHarnessApiCaller.class);
        tools.toolRegistryService = registry;
        tools.initialize();
        return registry;
    }
}
