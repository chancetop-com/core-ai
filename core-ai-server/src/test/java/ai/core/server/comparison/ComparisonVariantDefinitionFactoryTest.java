package ai.core.server.comparison;

import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonVariant;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.systemprompt.SystemPromptService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The factory must produce one config for both session-entry branches: the owned-editable branch
 * re-derives from the top-level fields while every other caller reads publishedConfig. A definition
 * filled on only one layer silently assembles an empty config (or the unfiltered tool list), so the
 * consistency of the two branches is the core regression this test locks down.
 *
 * @author stephen
 */
class ComparisonVariantDefinitionFactoryTest {
    @Test
    void ownerAndNonOwnerBranchesAssembleTheSameFrozenConfig() {
        var systemPromptService = mock(SystemPromptService.class);
        when(systemPromptService.resolveContent("sp-1")).thenReturn("resolved prompt");
        var comparison = comparisonWith(variant());

        var transientDefinition = ComparisonVariantDefinitionFactory.build(comparison, comparison.variantB, "tester-1", systemPromptService);

        var ownerExecutable = AgentDependencyAccessPolicy.executableSessionAgent(transientDefinition, "tester-1");
        var nonOwnerExecutable = AgentDependencyAccessPolicy.executableSessionAgent(transientDefinition, "tester-2");

        assertConfig(ownerExecutable.publishedConfig);
        assertConfig(nonOwnerExecutable.publishedConfig);
        // the marker only gates the published path; the owned path does not read it
        assertEquals(AgentDependencyAccessPolicy.CURRENT_SKILL_VALIDATION_VERSION,
            nonOwnerExecutable.publishedConfig.skillValidationVersion);
    }

    private void assertConfig(AgentPublishedConfig config) {
        assertEquals("variant-model", config.model);
        assertEquals("resolved prompt", config.systemPrompt);
        assertNull(config.systemPromptId);
        assertEquals(List.of("skill-1"), config.skillIds);
        assertEquals(List.of(builtinRef("tool-a")), config.tools);
        assertEquals(Boolean.FALSE, config.enableMemory);
    }

    @Test
    void effectiveWhitelistReplacesTheConfiguredToolSet() {
        var variant = variant();
        variant.config.tools = List.of(builtinRef("tool-a"), mcpRef("srv", "tool-b"));
        variant.effectiveTools = List.of(builtinRef("tool-a"));

        var definition = ComparisonVariantDefinitionFactory.build(
            comparisonWith(variant), variant, "tester-1", mock(SystemPromptService.class));

        assertEquals(List.of(builtinRef("tool-a")), definition.publishedConfig.tools);
        assertEquals(List.of(builtinRef("tool-a")), definition.tools);
    }

    @Test
    void unfrozenVariantToolsAreRejectedInsteadOfFallingBackToTheConfiguredSet() {
        var variant = variant();
        variant.effectiveTools = null;

        assertThrows(IllegalStateException.class, () -> ComparisonVariantDefinitionFactory.build(
            comparisonWith(variant), variant, "tester-1", mock(SystemPromptService.class)));
    }

    @Test
    void comparisonSessionsNeverUseTheSharedMemoryStore() {
        var variant = variant();
        variant.config.enableMemory = Boolean.TRUE;

        var definition = ComparisonVariantDefinitionFactory.build(
            comparisonWith(variant), variant, "tester-1", mock(SystemPromptService.class));

        assertNotEquals(Boolean.TRUE, definition.publishedConfig.enableMemory);
    }

    private AgentComparison comparisonWith(ComparisonVariant variant) {
        var comparison = new AgentComparison();
        comparison.id = "comparison-1";
        comparison.agentId = "agent-1";
        comparison.agentName = "Test Agent";
        comparison.variantB = variant;
        return comparison;
    }

    private ComparisonVariant variant() {
        var config = new AgentPublishedConfig();
        config.systemPromptId = "sp-1";
        config.model = "variant-model";
        config.skillIds = List.of("skill-1");
        config.tools = List.of(builtinRef("tool-a"));
        config.enableMemory = Boolean.TRUE;

        var variant = new ComparisonVariant();
        variant.label = "B";
        variant.config = config;
        variant.effectiveTools = List.of(builtinRef("tool-a"));
        return variant;
    }

    private ToolRef builtinRef(String toolId) {
        return ToolRef.of("builtin:" + toolId, ToolSourceType.BUILTIN);
    }

    private ToolRef mcpRef(String server, String toolName) {
        return ToolRef.of("mcp-tool:" + server + ":" + toolName, ToolSourceType.MCP);
    }
}
