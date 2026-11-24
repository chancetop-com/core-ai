package ai.core.server.agent;

import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DefinitionType;
import ai.core.server.util.IdLists;
import core.framework.util.Strings;
import core.framework.web.exception.ForbiddenException;

/**
 * Field application for an editable agent update. Extracted from AgentDefinitionService to keep the
 * service focused on authorization, CAS and response assembly.
 *
 * @author core-ai
 */
final class AgentUpdateHelper {
    static void apply(AgentDefinition entity, UpdateAgentRequest request, boolean admin) {
        if (request.name != null) {
            entity.name = request.name;
            entity.nameKey = AgentNameKey.normalize(entity.name);
        }
        if (request.description != null) entity.description = request.description;
        if (request.systemPrompt != null) entity.systemPrompt = request.systemPrompt;
        if (request.systemPromptId != null) entity.systemPromptId = request.systemPromptId;
        if (request.model != null) entity.model = request.model;
        if (request.multiModalModel != null) entity.multiModalModel = Strings.isBlank(request.multiModalModel) ? null : request.multiModalModel;
        if (request.preferCaptionPath != null) entity.preferCaptionPath = request.preferCaptionPath;
        if (request.temperature != null) entity.temperature = request.temperature;
        if (request.thinkingEffort != null) entity.thinkingEffort = AgentViewHelper.normalizeThinkingEffort(request.thinkingEffort);
        if (request.maxTurns != null) entity.maxTurns = request.maxTurns;
        if (request.timeoutSeconds != null) entity.timeoutSeconds = request.timeoutSeconds;
        if (request.tools != null) entity.tools = AgentViewHelper.toToolRefs(request.tools);
        if (request.inputTemplate != null) entity.inputTemplate = request.inputTemplate;
        if (request.variables != null) entity.variables = request.variables;
        if (request.responseSchema != null) entity.responseSchema = request.responseSchema;
        if (request.type != null) entity.type = DefinitionType.valueOf(request.type);
        if (request.subAgentIds != null) entity.subAgentIds = IdLists.cleanOrNull(request.subAgentIds);
        if (request.skillIds != null) entity.skillIds = IdLists.cleanOrNull(request.skillIds);
        if (request.sandboxConfig != null) entity.sandboxConfig = AgentViewHelper.fromSandboxConfigView(request.sandboxConfig);
        if (request.datasetConfig != null) entity.datasetConfig = AgentViewHelper.toDatasetConfigs(request.datasetConfig);
        if (request.enableMemory != null) entity.enableMemory = request.enableMemory;
        if (request.systemDefault != null) {
            if (Boolean.TRUE.equals(request.systemDefault) && !admin) {
                throw new ForbiddenException("only admin can set agents as system default");
            }
            entity.systemDefault = request.systemDefault;
        }
    }

    private AgentUpdateHelper() {
    }
}
