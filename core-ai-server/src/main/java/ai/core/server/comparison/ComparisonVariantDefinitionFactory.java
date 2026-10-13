package ai.core.server.comparison;

import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonVariant;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.systemprompt.SystemPromptService;

import core.framework.util.Strings;

import java.util.List;

/**
 * Builds the executable transient definition of a frozen comparison variant.
 * <p>
 * The session entry gate ({@code AgentDependencyAccessPolicy.executableSessionAgent}) re-derives the
 * config from the TOP-LEVEL editable fields when the caller owns the definition — which is always the
 * case here, because the transient carries the session owner as its {@code userId} — while every other
 * caller reads {@code publishedConfig}. Filling only one layer silently produces an empty config
 * (owner testers) or, if the top level kept the unfiltered tool list, would bypass the effective-tool
 * whitelist. Both layers are therefore filled from the same frozen config.
 *
 * @author stephen
 */
public final class ComparisonVariantDefinitionFactory {
    public static ComparisonVariant variantOf(AgentComparison comparison, String variant) {
        if ("a".equals(variant)) return comparison.variantA;
        if ("b".equals(variant)) return comparison.variantB;
        return null;
    }

    public static AgentDefinition build(AgentComparison comparison, ComparisonVariant variant, String userId,
                                        SystemPromptService systemPromptService) {
        var config = frozenConfig(variant, systemPromptService);
        var definition = new AgentDefinition();
        definition.id = comparison.agentId;
        definition.name = comparison.agentName;
        definition.userId = userId;
        definition.forkedFrom = comparison.agentForkedFrom;
        definition.type = DefinitionType.AGENT;
        definition.status = AgentStatus.PUBLISHED;
        definition.publishedConfig = config;
        mirrorEditableFields(definition, config);
        return definition;
    }

    private static AgentPublishedConfig frozenConfig(ComparisonVariant variant, SystemPromptService systemPromptService) {
        if (variant == null || variant.config == null) {
            throw new IllegalStateException("comparison variant is unavailable");
        }
        if (variant.effectiveTools == null) {
            throw new IllegalStateException("comparison variant tools are not frozen");
        }
        var config = AgentExecutableConfigFactory.fromPublishedConfig(variant.config);
        // the confirmed whitelist replaces the configured tool set: tools outside it must never assemble
        config.tools = List.copyOf(variant.effectiveTools);
        // the published-config gate on the non-owner branch requires a resolved prompt and a validated-skills marker
        if (!Strings.isBlank(config.systemPromptId)) {
            config.systemPrompt = systemPromptService.resolveContent(config.systemPromptId);
            config.systemPromptId = null;
        }
        AgentDependencyAccessPolicy.markPublishedSkillsValidated(config);
        // comparison arms must not read or write the shared memory store: two variants extracting memories
        // would pollute one agent's memory with two voices (design-agent-comparison-test 5.8)
        config.enableMemory = Boolean.FALSE;
        return config;
    }

    private static void mirrorEditableFields(AgentDefinition definition, AgentPublishedConfig config) {
        definition.systemPrompt = config.systemPrompt;
        definition.systemPromptId = config.systemPromptId;
        definition.model = config.model;
        definition.multiModalModel = config.multiModalModel;
        definition.preferCaptionPath = config.preferCaptionPath;
        definition.temperature = config.temperature;
        definition.thinkingEffort = config.thinkingEffort;
        definition.maxTurns = config.maxTurns;
        definition.timeoutSeconds = config.timeoutSeconds;
        definition.tools = config.tools;
        definition.skillIds = config.skillIds;
        definition.subAgentIds = config.subAgentIds;
        definition.inputTemplate = config.inputTemplate;
        definition.variables = config.variables;
        definition.responseSchema = config.responseSchema;
        definition.enableMemory = config.enableMemory;
        definition.sandboxConfig = config.sandboxConfig;
        definition.datasetConfig = config.datasetConfig;
    }

    private ComparisonVariantDefinitionFactory() {
    }
}
