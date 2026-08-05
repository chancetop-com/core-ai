package ai.core.server.session;

import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.util.IdLists;
import core.framework.mongo.MongoCollection;

/**
 * Security boundary for persisted agent session snapshots.
 */
final class SessionAgentSnapshotSecurity {
    static boolean isTrusted(SessionState state) {
        return state.agentSnapshotSecurityVersion != null
                && state.agentSnapshotSecurityVersion == SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION;
    }

    static boolean isSandboxBindingTrusted(SessionState state) {
        return state.sandboxBindingSecurityVersion != null
                && state.sandboxBindingSecurityVersion == SessionState.CURRENT_SANDBOX_BINDING_SECURITY_VERSION;
    }

    static String trustedUserId(SessionState state, String callerUserId) {
        if (hasText(callerUserId) && !callerUserId.equals(state.userId)) {
            throw new IllegalArgumentException("session is unavailable");
        }
        return hasText(callerUserId) ? callerUserId : state.userId;
    }

    static LegacyIdentity authorizeLegacyIdentity(String sessionId, SessionState legacyState,
                                                  String callerUserId,
                                                  ChatMessageService chatMessageService) {
        var meta = chatMessageService.getSessionMeta(sessionId);
        var persistedUserId = meta != null ? meta.userId : null;
        if (hasText(callerUserId) && hasText(persistedUserId) && !callerUserId.equals(persistedUserId)) {
            throw new IllegalArgumentException("session is unavailable");
        }
        var effectiveCallerUserId = hasText(callerUserId) ? callerUserId : persistedUserId;
        if (!hasText(effectiveCallerUserId)) throw new IllegalArgumentException("agent is unavailable");
        var agentId = meta != null ? meta.agentId : legacyState.agentConfig.agentId;
        if (!hasText(agentId)) throw new IllegalArgumentException("agent is unavailable");
        return new LegacyIdentity(effectiveCallerUserId, agentId);
    }

    static SessionState rederiveLegacyState(String sessionId, LegacyIdentity identity,
                                            MongoCollection<AgentDefinition> agentDefinitionCollection,
                                            SessionSkillManager skillManager) {
        var currentDefinition = agentDefinitionCollection.get(identity.agentId()).orElse(null);
        var ownedEditable = AgentDependencyAccessPolicy.isOwnedEditable(currentDefinition, identity.userId());
        var executable = AgentDependencyAccessPolicy.executableSessionAgent(currentDefinition,
                identity.userId());
        if (ownedEditable) {
            skillManager.resolveAccessibleDefinitionSkills(executable, identity.userId());
        }
        var safeState = new SessionState();
        safeState.agentSnapshotSecurityVersion = SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION;
        safeState.sessionId = sessionId;
        safeState.userId = identity.userId();
        safeState.fromAgent = true;
        safeState.agentConfig = buildSnapshot(executable);
        var executableConfig = executable.publishedConfig;
        safeState.subAgentIds = IdLists.clean(executableConfig != null
                ? executableConfig.subAgentIds : executable.subAgentIds);
        return safeState;
    }

    static SessionState.AgentConfigSnapshot buildSnapshot(AgentDefinition definition) {
        var published = definition.publishedConfig;
        var snapshot = new SessionState.AgentConfigSnapshot();
        snapshot.agentId = definition.id;
        snapshot.agentName = definition.name;
        snapshot.systemPrompt = published != null ? published.systemPrompt : definition.systemPrompt;
        snapshot.systemPromptId = published != null ? published.systemPromptId : definition.systemPromptId;
        snapshot.model = published != null ? published.model : definition.model;
        snapshot.multiModalModel = published != null ? published.multiModalModel : definition.multiModalModel;
        snapshot.preferCaptionPath = published != null ? published.preferCaptionPath : definition.preferCaptionPath;
        snapshot.temperature = published != null ? published.temperature : definition.temperature;
        snapshot.thinkingEffort = published != null ? published.thinkingEffort : definition.thinkingEffort;
        snapshot.maxTurns = published != null ? published.maxTurns : definition.maxTurns;
        snapshot.inputTemplate = published != null ? published.inputTemplate : definition.inputTemplate;
        snapshot.variables = published != null ? published.variables : definition.variables;
        snapshot.tools = published != null ? published.tools : definition.tools;
        snapshot.skillIds = published != null ? published.skillIds : definition.skillIds;
        snapshot.datasetConfig = published != null ? published.datasetConfig : definition.datasetConfig;
        snapshot.sandboxConfig = published != null ? published.sandboxConfig : definition.sandboxConfig;
        return snapshot;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private SessionAgentSnapshotSecurity() {
    }

    record LegacyIdentity(String userId, String agentId) {
    }
}
