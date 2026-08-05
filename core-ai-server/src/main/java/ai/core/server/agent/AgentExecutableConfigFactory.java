package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.util.IdLists;
import core.framework.json.JSON;

public final class AgentExecutableConfigFactory {
    public static AgentPublishedConfig fromEditableDefinition(AgentDefinition definition) {
        var config = new AgentPublishedConfig();
        config.systemPrompt = definition.systemPrompt;
        config.systemPromptId = definition.systemPromptId;
        config.model = definition.model;
        config.multiModalModel = definition.multiModalModel;
        config.preferCaptionPath = definition.preferCaptionPath;
        config.temperature = definition.temperature;
        config.thinkingEffort = definition.thinkingEffort;
        config.maxTurns = definition.maxTurns;
        config.timeoutSeconds = definition.timeoutSeconds;
        config.tools = definition.tools;
        config.skillIds = IdLists.cleanOrNull(definition.skillIds);
        config.subAgentIds = IdLists.cleanOrNull(definition.subAgentIds);
        config.inputTemplate = definition.inputTemplate;
        config.variables = definition.variables;
        config.responseSchema = definition.responseSchema;
        config.enableMemory = definition.enableMemory;
        config.sandboxConfig = definition.sandboxConfig;
        config.datasetConfig = definition.datasetConfig;
        return JSON.fromJSON(AgentPublishedConfig.class, JSON.toJSON(config));
    }

    public static AgentPublishedConfig fromPublishedConfig(AgentPublishedConfig config) {
        if (config == null) throw new IllegalArgumentException("published agent config is missing");
        return JSON.fromJSON(AgentPublishedConfig.class, JSON.toJSON(config));
    }

    private AgentExecutableConfigFactory() {
    }
}
