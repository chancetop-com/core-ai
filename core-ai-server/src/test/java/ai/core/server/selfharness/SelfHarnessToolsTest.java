package ai.core.server.selfharness;

import ai.core.server.tool.ToolRegistryService;
import ai.core.server.domain.migration.SchemaMigrationVDefaultAgent;
import ai.core.tool.ToolCall;

import org.junit.jupiter.api.Test;

import java.util.List;

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
