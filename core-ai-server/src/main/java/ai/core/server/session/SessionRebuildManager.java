package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.sse.SseEventBridge;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCall;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class SessionRebuildManager {
    private final Logger logger = LoggerFactory.getLogger(SessionRebuildManager.class);

    private final ChatMessageService chatMessageService;
    private final MongoCollection<AgentDefinition> agentDefinitionCollection;
    private final SessionSkillManager skillManager;
    private final SessionSubAgentManager subAgentManager;
    private final SandboxService sandboxService;
    private final ChatArtifactSetup artifactSetup;
    private final ToolRegistryService toolRegistryService;
    private final SystemPromptService systemPromptService;
    private final EventPublisher eventPublisher;
    private final SessionOwnershipRegistry ownershipRegistry;

    public SessionRebuildManager(Deps deps) {
        this.chatMessageService = deps.chatMessageService;
        this.agentDefinitionCollection = deps.agentDefinitionCollection;
        this.skillManager = deps.skillManager;
        this.subAgentManager = deps.subAgentManager;
        this.sandboxService = deps.sandboxService;
        this.artifactSetup = deps.artifactSetup;
        this.toolRegistryService = deps.toolRegistryService;
        this.systemPromptService = deps.systemPromptService;
        this.eventPublisher = deps.eventPublisher;
        this.ownershipRegistry = deps.ownershipRegistry;
    }

    public SessionState buildStateFromDb(String sessionId) {
        var meta = chatMessageService.getSessionMeta(sessionId);
        if (meta == null) return null;
        var state = new SessionState();
        state.sessionId = sessionId;
        state.userId = meta.userId;
        if (meta.agentId == null) {
            state.fromAgent = false;
            populateDynamicLoads(state, meta);
            return state;
        }
        var definition = agentDefinitionCollection.get(meta.agentId).orElse(null);
        if (definition == null) {
            logger.warn("agent definition not found for DB rebuild, sessionId={}, agentId={}", sessionId, meta.agentId);
            state.fromAgent = false;
            populateDynamicLoads(state, meta);
            return state;
        }
        state.fromAgent = true;
        state.agentConfig = buildSnapshotFromDefinition(definition);
        populateDynamicLoads(state, meta);
        return state;
    }

    private void populateDynamicLoads(SessionState state, ai.core.server.domain.ChatSession meta) {
        if (meta.loadedTools != null && !meta.loadedTools.isEmpty()) {
            state.tools = meta.loadedTools;
            logger.info("loaded {} tool ref(s) from DB for session {}, refs={}", meta.loadedTools.size(), meta.id, meta.loadedTools);
        }
        if (meta.loadedSkillIds != null && !meta.loadedSkillIds.isEmpty()) {
            state.skillIds = meta.loadedSkillIds;
        }
        if (meta.loadedSubAgentIds != null && !meta.loadedSubAgentIds.isEmpty()) {
            state.subAgentIds = meta.loadedSubAgentIds;
        }
    }

    private SessionState.AgentConfigSnapshot buildSnapshotFromDefinition(AgentDefinition def) {
        var pub = def.publishedConfig;
        var snapshot = new SessionState.AgentConfigSnapshot();
        snapshot.agentName = def.name;
        snapshot.systemPrompt = pub != null && pub.systemPrompt != null ? pub.systemPrompt : def.systemPrompt;
        snapshot.systemPromptId = pub != null && pub.systemPromptId != null ? pub.systemPromptId : def.systemPromptId;
        snapshot.model = pub != null && pub.model != null ? pub.model : def.model;
        snapshot.multiModalModel = pub != null && pub.multiModalModel != null ? pub.multiModalModel : def.multiModalModel;
        snapshot.temperature = pub != null && pub.temperature != null ? pub.temperature : def.temperature;
        snapshot.maxTurns = pub != null && pub.maxTurns != null ? pub.maxTurns : def.maxTurns;
        snapshot.inputTemplate = pub != null && pub.inputTemplate != null ? pub.inputTemplate : def.inputTemplate;
        snapshot.variables = pub != null && pub.variables != null ? pub.variables : def.variables;
        snapshot.tools = pub != null && pub.tools != null && !pub.tools.isEmpty() ? pub.tools : def.tools;
        return snapshot;
    }

    public InProcessAgentSession rebuildSession(String sessionId, SessionState state) {
        try {
            if (state.fromAgent && state.agentConfig != null) {
                return rebuildFromSnapshot(sessionId, state.agentConfig, state.userId, state);
            } else {
                return rebuildFromConfig(sessionId, state.config, state.userId, state);
            }
        } catch (Exception e) {
            logger.warn("failed to rebuild session, sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    private InProcessAgentSession rebuildFromSnapshot(String sessionId, SessionState.AgentConfigSnapshot snapshot, String userId, SessionState state) {
        var config = new SessionConfig();
        config.systemPrompt = snapshot.systemPromptId != null ? systemPromptService.resolveContent(snapshot.systemPromptId) : snapshot.systemPrompt;
        config.model = snapshot.model;
        config.multiModalModel = snapshot.multiModalModel;
        config.temperature = snapshot.temperature;
        config.maxTurns = snapshot.maxTurns;
        List<ToolCall> tools = (snapshot.tools != null && !snapshot.tools.isEmpty()) ? toolRegistryService.resolveToolRefs(snapshot.tools) : List.of();
        return doRebuild(sessionId, config, tools, userId, snapshot.agentName, state);
    }

    private InProcessAgentSession rebuildFromConfig(String sessionId, SessionConfig config, String userId, SessionState state) {
        return doRebuild(sessionId, config, null, userId, null, state);
    }

    private InProcessAgentSession doRebuild(String sessionId, SessionConfig config, List<ToolCall> tools, String userId, String agentName, SessionState state) {
        var start = System.currentTimeMillis();
        var toolCount = tools != null ? tools.size() : 0;
        var skillCount = state != null && state.skillIds != null ? state.skillIds.size() : 0;
        var subAgentCount = state != null && state.subAgentIds != null ? state.subAgentIds.size() : 0;
        var dynamicToolCount = state != null && state.tools != null ? state.tools.size() : 0;
        logger.info("doRebuild start, sessionId={}, fromAgent={}, baseTools={}, dynamicTools={}, skills={}, subAgents={}",
                sessionId, state != null && state.fromAgent, toolCount, dynamicToolCount, skillCount, subAgentCount);
        var context = userId != null ? ExecutionContext.builder().sessionId(sessionId).userId(userId).build() : null;
        var sandboxOn = context != null && sandboxService.isSandboxEnabled(null);
        var agent = subAgentManager.buildAgent(artifactSetup.appendArtifactInstructions(config, sandboxOn),
                sandboxOn ? artifactSetup.withSubmitArtifactsTool(tools, sessionId, userId, true) : tools,
                context, agentName);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        session.setOnIdle(() -> renewSessionOwnership(sessionId));
        session.onEvent(chatMessageService.listener(sessionId));
        if (eventPublisher != null) {
            session.onEvent(new SseEventBridge(sessionId, eventPublisher));
        }
        registerSessionFromDb(sessionId, userId);
        if (context != null) {
            var sandbox = sandboxService.createSandbox(null, sessionId, userId, session::dispatchEvent);
            if (sandbox != null) context.sandbox(sandbox);
        }
        restoreAgentHistory(agent, sessionId);
        restoreDynamicallyLoaded(state, sessionId, session);
        logger.info("doRebuild done, sessionId={}, elapsedMs={}", sessionId, System.currentTimeMillis() - start);
        return session;
    }

    private void renewSessionOwnership(String sessionId) {
        if (ownershipRegistry != null) {
            ownershipRegistry.claimOrRenew(sessionId);
        }
    }

    private void restoreDynamicallyLoaded(SessionState state, String sessionId, InProcessAgentSession session) {
        if (state == null) return;
        if (state.tools != null && !state.tools.isEmpty()) {
            try {
                logger.info("restore tools: {} ref(s) to resolve for session {}, refs={}", state.tools.size(), sessionId, state.tools);
                var resolved = toolRegistryService.resolveToolRefs(state.tools);
                if (!resolved.isEmpty()) {
                    session.loadTools(resolved);
                    logger.info("restored {} dynamically loaded tools for session {}", resolved.size(), sessionId);
                } else {
                    logger.warn("restore tools: resolution returned empty for {} ref(s), sessionId={}, refs={}", state.tools.size(), sessionId, state.tools);
                }
            } catch (Exception e) {
                logger.warn("failed to restore dynamically loaded tools, sessionId={}", sessionId, e);
            }
        }
        if (state.skillIds != null && !state.skillIds.isEmpty()) {
            try {
                skillManager.applySkillsToSession(session, state.skillIds);
                logger.info("restored {} dynamically loaded skills for session {}", state.skillIds.size(), sessionId);
            } catch (Exception e) {
                logger.warn("failed to restore dynamically loaded skills, sessionId={}", sessionId, e);
            }
        }
        if (state.subAgentIds != null && !state.subAgentIds.isEmpty()) {
            try {
                var definitions = state.subAgentIds.stream()
                        .map(id -> agentDefinitionCollection.get(id).orElse(null))
                        .filter(def -> def != null)
                        .toList();
                if (!definitions.isEmpty()) {
                    subAgentManager.applySubAgentsToSession(session, definitions);
                    logger.info("restored {} dynamically loaded sub-agents for session {}", definitions.size(), sessionId);
                }
            } catch (Exception e) {
                logger.warn("failed to restore dynamically loaded sub-agents, sessionId={}", sessionId, e);
            }
        }
    }

    private void registerSessionFromDb(String sessionId, String userId) {
        var meta = chatMessageService.getSessionMeta(sessionId);
        if (meta != null) {
            chatMessageService.registerSession(sessionId, new ChatMessageService.SessionMeta(
                    meta.userId != null ? meta.userId : userId,
                    meta.agentId,
                    meta.source != null ? meta.source : "chat",
                    meta.scheduleId,
                    meta.apiKeyId));
        } else {
            chatMessageService.registerSession(sessionId, ChatMessageService.SessionMeta.of(userId, null, "chat"));
        }
    }

    private void restoreAgentHistory(Agent agent, String sessionId) {
        try {
            var records = chatMessageService.history(sessionId);
            if (records.isEmpty()) return;
            List<ai.core.llm.domain.Message> restored = new ArrayList<>(records.size());
            for (var r : records) {
                if (r.content == null || r.content.isBlank()) continue;
                var role = "user".equals(r.role) ? ai.core.llm.domain.RoleType.USER : ai.core.llm.domain.RoleType.ASSISTANT;
                restored.add(ai.core.llm.domain.Message.of(role, r.content));
            }
            if (!restored.isEmpty()) {
                agent.restoreHistory(restored);
                logger.info("restored {} historical messages for session {}", restored.size(), sessionId);
            }
        } catch (Exception e) {
            logger.warn("failed to restore agent history, sessionId={}", sessionId, e);
        }
    }

    public record Deps(ChatMessageService chatMessageService,
                        MongoCollection<AgentDefinition> agentDefinitionCollection,
                        SessionSkillManager skillManager,
                        SessionSubAgentManager subAgentManager,
                        SandboxService sandboxService,
                        ChatArtifactSetup artifactSetup,
                        ToolRegistryService toolRegistryService,
                        SystemPromptService systemPromptService,
                        EventPublisher eventPublisher,
                        SessionOwnershipRegistry ownershipRegistry) { }
}
