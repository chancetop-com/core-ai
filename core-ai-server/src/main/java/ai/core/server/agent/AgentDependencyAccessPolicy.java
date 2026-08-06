package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.util.IdLists;

/**
 * Central access and snapshot policy for executable Agent dependencies.
 *
 * @author Xander
 */
public final class AgentDependencyAccessPolicy {
    public static final int CURRENT_SKILL_VALIDATION_VERSION = 1;

    public static boolean isOwnedEditable(AgentDefinition definition, String userId) {
        return userId != null
            && definition != null
            && userId.equals(definition.userId)
            && !Boolean.TRUE.equals(definition.systemDefault);
    }

    public static boolean hasUsablePublishedConfig(AgentDefinition definition) {
        return definition != null
                && definition.status == AgentStatus.PUBLISHED
                && hasValidatedPublishedSkills(definition.publishedConfig)
                && (definition.publishedConfig.systemPromptId == null
                || definition.publishedConfig.systemPromptId.isBlank());
    }

    public static boolean hasValidatedPublishedSkills(AgentPublishedConfig config) {
        return config != null
            && (IdLists.clean(config.skillIds).isEmpty()
                || Integer.valueOf(CURRENT_SKILL_VALIDATION_VERSION).equals(config.skillValidationVersion));
    }

    public static void markPublishedSkillsValidated(AgentPublishedConfig config) {
        if (config == null) throw unavailableAgent();
        config.skillValidationVersion = CURRENT_SKILL_VALIDATION_VERSION;
    }

    public static boolean hasUsablePublishedSubAgent(AgentDefinition definition) {
        return definition != null
            && definition.type == DefinitionType.AGENT
            && hasUsablePublishedConfig(definition);
    }

    public static boolean hasUsablePublishedLlmCall(AgentDefinition definition) {
        return definition != null
            && definition.type == DefinitionType.LLM_CALL
            && hasUsablePublishedConfig(definition);
    }

    public static boolean isLlmCallRef(ToolRef ref) {
        if (ref == null) return false;
        if (ref.id != null && ref.id.startsWith(ToolRef.LLM_CALL_PREFIX)) return true;
        return ref.type == ToolSourceType.LLM_CALL;
    }

    public static String requireLlmCallDefinitionId(ToolRef ref) {
        if (ref == null || ref.id == null || !ref.id.startsWith(ToolRef.LLM_CALL_PREFIX) || !isLlmCallRef(ref)) {
            throw unavailableLlmCall();
        }
        String definitionId = ref.id.substring(ToolRef.LLM_CALL_PREFIX.length());
        if (definitionId.isBlank() || !definitionId.equals(definitionId.trim())) {
            throw unavailableLlmCall();
        }
        return definitionId;
    }

    public static void requireLlmCallCaller(String callerUserId) {
        if (callerUserId == null || callerUserId.isBlank()) throw unavailableLlmCall();
    }

    public static AgentDefinition executableLlmCall(AgentDefinition definition, String callerUserId) {
        requireLlmCallCaller(callerUserId);
        if (definition == null || definition.type != DefinitionType.LLM_CALL) {
            throw unavailableLlmCall();
        }
        var config = isOwnedEditable(definition, callerUserId)
            ? AgentExecutableConfigFactory.fromEditableDefinition(definition)
            : hasUsablePublishedLlmCall(definition)
                ? AgentExecutableConfigFactory.fromPublishedConfig(definition.publishedConfig)
                : null;
        if (config == null) throw unavailableLlmCall();

        var executable = new AgentDefinition();
        executable.id = definition.id;
        executable.userId = definition.userId;
        executable.name = definition.name;
        executable.description = definition.description;
        executable.type = DefinitionType.LLM_CALL;
        executable.status = definition.status;
        executable.systemDefault = definition.systemDefault;
        executable.publishedConfig = config;
        executable.createdAt = definition.createdAt;
        executable.updatedAt = definition.updatedAt;
        executable.publishedAt = definition.publishedAt;
        return executable;
    }

    public static AgentDefinition executableTopLevelAgent(AgentDefinition definition, String callerUserId) {
        return executableTopLevel(definition, callerUserId, false);
    }

    public static AgentDefinition executableTopLevelCallable(AgentDefinition definition, String callerUserId) {
        return executableTopLevel(definition, callerUserId, true);
    }

    public static AgentDefinition executableSessionAgent(AgentDefinition definition, String callerUserId) {
        var executable = executableTopLevelAgent(definition, callerUserId);
        return isOwnedEditable(definition, callerUserId)
            ? detachedConfig(definition, callerUserId,
                AgentExecutableConfigFactory.fromEditableDefinition(definition))
            : executable;
    }

    public static AgentDefinition executablePublishedLlmCall(AgentDefinition definition, String callerUserId) {
        requireLlmCallCaller(callerUserId);
        if (!hasUsablePublishedLlmCall(definition)) throw unavailableLlmCall();
        return detachedPublished(definition, callerUserId);
    }

    private static AgentDefinition executableTopLevel(AgentDefinition definition, String callerUserId,
                                                      boolean allowLlmCall) {
        if (definition == null
            || callerUserId == null || callerUserId.isBlank()
            || definition.type != DefinitionType.AGENT
               && (!allowLlmCall || definition.type != DefinitionType.LLM_CALL)) {
            throw unavailableAgent();
        }
        if (isOwnedEditable(definition, callerUserId)) return definition;
        if (!hasUsablePublishedConfig(definition)) throw unavailableAgent();

        return detachedPublished(definition, callerUserId);
    }

    private static AgentDefinition detachedPublished(AgentDefinition definition, String callerUserId) {
        return detachedConfig(definition, callerUserId,
            AgentExecutableConfigFactory.fromPublishedConfig(definition.publishedConfig));
    }

    private static AgentDefinition detachedConfig(AgentDefinition definition, String callerUserId,
                                                   AgentPublishedConfig config) {
        var executable = new AgentDefinition();
        executable.id = definition.id;
        executable.userId = callerUserId;
        executable.name = definition.name;
        executable.description = definition.description;
        executable.type = definition.type;
        executable.status = definition.status;
        executable.systemDefault = definition.systemDefault;
        executable.publishedConfig = config;
        executable.sandboxConfig = executable.publishedConfig.sandboxConfig;
        executable.createdAt = definition.createdAt;
        executable.updatedAt = definition.updatedAt;
        executable.publishedAt = definition.publishedAt;
        return executable;
    }

    private static IllegalArgumentException unavailableLlmCall() {
        return new IllegalArgumentException("LLM call tool is unavailable");
    }

    private static IllegalArgumentException unavailableAgent() {
        return new IllegalArgumentException("agent is unavailable");
    }

    private AgentDependencyAccessPolicy() {
    }
}
