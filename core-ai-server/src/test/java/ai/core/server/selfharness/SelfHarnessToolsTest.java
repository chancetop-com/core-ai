package ai.core.server.selfharness;

import ai.core.server.tool.ToolRegistryService;
import org.junit.jupiter.api.Test;

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
                argThat(tools -> tools.stream().anyMatch(tool -> "create_dataset_report".equals(tool.getName()))),
                argThat(groups -> "Datasets".equals(groups.get("create_dataset"))
                        && "Datasets".equals(groups.get("create_dataset_report"))));
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
