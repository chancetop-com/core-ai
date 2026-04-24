package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.ServerSkillTool;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.ReadSkillResourceTool;
import ai.core.tool.tools.SubAgentToolCall;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.session.InProcessAgentSession;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class AgentSessionManager {
    private final Logger logger = LoggerFactory.getLogger(AgentSessionManager.class);
    private final ConcurrentMap<String, InProcessAgentSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SessionSkillState> sessionSkillStates = new ConcurrentHashMap<>();

    /** Per-session skill whitelist + registry. loadSkills appends to the id set and invalidates
     *  the registry cache so the Agent only ever sees skills explicitly attached to this session. */
    private static final class SessionSkillState {
        final Set<String> allowedIds = ConcurrentHashMap.newKeySet();
        final SkillRegistry registry = new SkillRegistry();
    }

    @Inject
    LLMProviders llmProviders;
    @Inject
    PersistenceProviders persistenceProviders;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    MongoSkillProvider mongoSkillProvider;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    SkillService skillService;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    SessionChannelService sessionChannelService;
    @Inject
    SandboxService sandboxService;
    @Inject
    SystemPromptService systemPromptService;
    @Inject
    SkillArchiveBuilder skillArchiveBuilder;

    private void attachSessionListeners(InProcessAgentSession session, String sessionId) {
        session.onEvent(chatMessageService.listener(sessionId));
        session.onEvent(new ai.core.server.web.sse.SseEventBridge(sessionId, sessionChannelService));
    }

    public String createSession(SessionConfig config, String userId) {
        return createSession(config, userId, "chat");
    }

    public String createSession(SessionConfig config, String userId, String source) {
        var sessionId = UUID.randomUUID().toString();
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).build();
        var agent = buildAgent(config, null, context, null);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        attachSessionListeners(session, sessionId);
        chatMessageService.registerSession(sessionId, ChatMessageService.SessionMeta.of(userId, null, source));

        // Create sandbox after session so events can be dispatched
        var sandbox = sandboxService.createSandbox(null, sessionId, userId, session::dispatchEvent);
        if (sandbox != null) {
            context.sandbox(sandbox);
        }

        sessions.put(sessionId, session);
        return sessionId;
    }

    public SessionCreationResult createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId) {
        return createSessionFromAgent(definition, overrides, userId, "chat");
    }

    public SessionCreationResult createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId, String source) {
        var config = toSessionConfig(definition);
        if (overrides != null) {
            if (overrides.model != null) config.model = overrides.model;
            if (overrides.temperature != null) config.temperature = overrides.temperature;
            if (overrides.systemPrompt != null) config.systemPrompt = overrides.systemPrompt;
            if (overrides.maxTurns != null) config.maxTurns = overrides.maxTurns;
        }
        List<ToolCall> tools;
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null && !definition.publishedConfig.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.publishedConfig.tools);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.tools);
        } else {
            tools = List.of();
        }

        var sessionId = UUID.randomUUID().toString();
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).build();
        var agent = buildAgent(config, tools.isEmpty() ? null : tools, context, definition.name);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        attachSessionListeners(session, sessionId);
        chatMessageService.registerSession(sessionId, ChatMessageService.SessionMeta.of(userId, definition.id, source));

        // Create sandbox after session so events can be dispatched
        var sandboxConfig = sandboxService.getEffectiveConfig(definition);
        var sandbox = sandboxService.createSandbox(sandboxConfig, sessionId, userId, session::dispatchEvent);
        if (sandbox != null) {
            context.sandbox(sandbox);
        }

        sessions.put(sessionId, session);

        // Auto-load skills and subagents configured in the agent definition
        var loadedSkills = loadSkillsFromDefinition(sessionId, definition);
        var loadedSubAgents = loadSubAgentsFromDefinition(session, definition);

        return new SessionCreationResult(sessionId, loadedSubAgents, loadedSkills);
    }

    public InProcessAgentSession getSession(String sessionId) {
        return getSession(sessionId, null);
    }

    public InProcessAgentSession getSession(String sessionId, SessionState state) {
        var session = sessions.get(sessionId);
        if (session != null) return session;

        var effectiveState = state != null ? state : buildStateFromDb(sessionId);
        if (effectiveState != null) {
            logger.info("session not found locally, attempting to rebuild, sessionId={}", sessionId);
            session = rebuildSession(sessionId, effectiveState);
            if (session != null) {
                sessions.put(sessionId, session);
                logger.info("session rebuilt successfully, sessionId={}", sessionId);
                return session;
            }
        }

        throw new NotFoundException("session not found, sessionId=" + sessionId);
    }

    private SessionState buildStateFromDb(String sessionId) {
        var meta = chatMessageService.getSessionMeta(sessionId);
        if (meta == null) return null;
        var state = new SessionState();
        state.sessionId = sessionId;
        state.userId = meta.userId;
        if (meta.agentId == null) {
            state.fromAgent = false;
            return state;
        }
        var definition = agentDefinitionCollection.get(meta.agentId).orElse(null);
        if (definition == null) {
            logger.warn("agent definition not found for DB rebuild, sessionId={}, agentId={}", sessionId, meta.agentId);
            state.fromAgent = false;
            return state;
        }
        state.fromAgent = true;
        state.agentConfig = buildSnapshotFromDefinition(definition);
        return state;
    }

    private SessionState.AgentConfigSnapshot buildSnapshotFromDefinition(AgentDefinition def) {
        var pub = def.publishedConfig;
        var snapshot = new SessionState.AgentConfigSnapshot();
        snapshot.agentName = def.name;
        snapshot.systemPrompt = pub != null && pub.systemPrompt != null ? pub.systemPrompt : def.systemPrompt;
        snapshot.systemPromptId = pub != null && pub.systemPromptId != null ? pub.systemPromptId : def.systemPromptId;
        snapshot.model = pub != null && pub.model != null ? pub.model : def.model;
        snapshot.temperature = pub != null && pub.temperature != null ? pub.temperature : def.temperature;
        snapshot.maxTurns = pub != null && pub.maxTurns != null ? pub.maxTurns : def.maxTurns;
        snapshot.inputTemplate = pub != null && pub.inputTemplate != null ? pub.inputTemplate : def.inputTemplate;
        snapshot.variables = pub != null && pub.variables != null ? pub.variables : def.variables;
        snapshot.tools = pub != null && pub.tools != null && !pub.tools.isEmpty() ? pub.tools : def.tools;
        return snapshot;
    }

    private InProcessAgentSession rebuildSession(String sessionId, SessionState state) {
        try {
            if (state.fromAgent && state.agentConfig != null) {
                return rebuildFromSnapshot(sessionId, state.agentConfig, state.userId);
            } else {
                return rebuildFromConfig(sessionId, state.config, state.userId);
            }
        } catch (Exception e) {
            logger.warn("failed to rebuild session, sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    private InProcessAgentSession rebuildFromSnapshot(String sessionId, SessionState.AgentConfigSnapshot snapshot, String userId) {
        var config = new SessionConfig();
        config.systemPrompt = snapshot.systemPromptId != null ? systemPromptService.resolveContent(snapshot.systemPromptId) : snapshot.systemPrompt;
        config.model = snapshot.model;
        config.temperature = snapshot.temperature;
        config.maxTurns = snapshot.maxTurns;

        List<ToolCall> tools = (snapshot.tools != null && !snapshot.tools.isEmpty())
                ? toolRegistryService.resolveToolRefs(snapshot.tools)
                : List.of();
        var context = userId != null
                ? ExecutionContext.builder().sessionId(sessionId).userId(userId).build()
                : null;
        var agent = buildAgent(config, tools.isEmpty() ? null : tools, context, snapshot.agentName);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        attachSessionListeners(session, sessionId);
        registerSessionFromDb(sessionId, userId);

        if (context != null) {
            var sandbox = sandboxService.createSandbox(null, sessionId, userId, session::dispatchEvent);
            if (sandbox != null) {
                context.sandbox(sandbox);
            }
        }

        restoreAgentHistory(agent, sessionId);
        return session;
    }

    private InProcessAgentSession rebuildFromConfig(String sessionId, SessionConfig config, String userId) {
        var context = userId != null
                ? ExecutionContext.builder().sessionId(sessionId).userId(userId).build()
                : null;
        var agent = buildAgent(config, null, context, null);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        attachSessionListeners(session, sessionId);
        registerSessionFromDb(sessionId, userId);

        if (context != null) {
            var sandbox = sandboxService.createSandbox(null, sessionId, userId, session::dispatchEvent);
            if (sandbox != null) {
                context.sandbox(sandbox);
            }
        }

        restoreAgentHistory(agent, sessionId);
        return session;
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
            List<ai.core.llm.domain.Message> restored = new java.util.ArrayList<>(records.size());
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

    public void closeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) session.close();
        sessionSkillStates.remove(sessionId);
        sandboxService.releaseSandbox(sessionId);
        chatMessageService.onSessionClosed(sessionId);
    }

    public List<String> loadToolRefs(String sessionId, List<ToolRef> toolRefs) {
        var session = getSession(sessionId);
        var tools = toolRegistryService.resolveToolRefs(toolRefs);
        if (tools.isEmpty()) {
            throw new NotFoundException("no tools found for refs: " + toolRefs);
        }
        session.loadTools(tools);
        return tools.stream().map(ToolCall::getName).toList();
    }

    public List<String> unloadSkills(String sessionId, List<String> skillIds) {
        var state = sessionSkillStates.get(sessionId);
        if (state == null || skillIds == null || skillIds.isEmpty()) {
            return state == null ? List.of() : List.copyOf(state.allowedIds);
        }
        state.allowedIds.removeAll(skillIds);
        state.registry.invalidateCache();
        return List.copyOf(state.allowedIds);
    }

    private List<String> loadSkillsFromDefinition(String sessionId, AgentDefinition definition) {
        var skillIds = definition.publishedConfig != null && definition.publishedConfig.skillIds != null
                ? definition.publishedConfig.skillIds
                : definition.skillIds;
        if (skillIds == null || skillIds.isEmpty()) return List.of();
        try {
            return loadSkills(sessionId, skillIds);
        } catch (Exception e) {
            logger.warn("failed to load skills from definition, sessionId={}, skillIds={}", sessionId, skillIds, e);
            return List.of();
        }
    }

    public List<String> loadSkills(String sessionId, List<String> skillIds) {
        var session = getSession(sessionId);
        var skills = skillService.resolveSkills(skillIds);
        if (skills.isEmpty()) {
            throw new NotFoundException("no skills found for ids: " + skillIds);
        }
        var state = sessionSkillStates.computeIfAbsent(sessionId, k -> {
            var fresh = new SessionSkillState();
            fresh.registry.addProvider(mongoSkillProvider.scoped(fresh.allowedIds));
            ToolCall skillTool = ServerSkillTool.builder()
                .registry(fresh.registry)
                .skillService(skillService)
                .archiveBuilder(skillArchiveBuilder)
                .build();
            ToolCall readResourceTool = ReadSkillResourceTool.builder().registry(fresh.registry).build();
            session.loadTools(List.of(skillTool, readResourceTool));
            return fresh;
        });
        state.allowedIds.addAll(skillIds);
        state.registry.invalidateCache();
        return skills.stream().map(SkillMetadata::getQualifiedName).toList();
    }

    public List<String> loadSubAgents(String sessionId, List<AgentDefinition> definitions) {
        var session = getSession(sessionId);
        var names = new java.util.ArrayList<String>();
        for (var definition : definitions) {
            var subAgent = buildSubAgent(definition);
            var subAgentToolCall = SubAgentToolCall.builder().subAgent(subAgent).build();
            session.loadTools(List.of(subAgentToolCall));
            names.add(definition.name);
        }
        return names;
    }

    private List<String> loadSubAgentsFromDefinition(InProcessAgentSession session, AgentDefinition definition) {
        var subAgentIds = definition.subAgentIds;
        if (subAgentIds == null || subAgentIds.isEmpty()) {
            return List.of();
        }
        var names = new java.util.ArrayList<String>();
        for (var subAgentId : subAgentIds) {
            try {
                var subAgentDef = agentDefinitionCollection.get(subAgentId)
                        .orElseThrow(() -> new RuntimeException("subagent not found, id=" + subAgentId));
                var subAgent = buildSubAgent(subAgentDef);
                var subAgentToolCall = SubAgentToolCall.builder().subAgent(subAgent).build();
                session.loadTools(List.of(subAgentToolCall));
                names.add(subAgentDef.name);
                logger.info("loaded subagent {} for session {}", subAgentDef.name, session.id());
            } catch (Exception e) {
                logger.warn("failed to load subagent {} for session {}: {}", subAgentId, session.id(), e.getMessage());
            }
        }
        return names;
    }

    private Agent buildSubAgent(AgentDefinition definition) {
        var config = toSessionConfig(definition);
        List<ToolCall> tools;
        if (definition.publishedConfig != null && definition.publishedConfig.tools != null && !definition.publishedConfig.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.publishedConfig.tools);
        } else if (definition.tools != null && !definition.tools.isEmpty()) {
            tools = toolRegistryService.resolveToolRefs(definition.tools);
        } else {
            tools = List.of();
        }

        var builder = Agent.builder()
                .name(definition.name.replaceAll("\\s+", "-"))
                .description(definition.description != null ? definition.description : definition.name)
                .llmProvider(llmProviders.getProvider())
                .toolCalls(tools.isEmpty() ? BuiltinTools.ALL : tools)
                .temperature(config.temperature != null ? config.temperature : 0.8);

        if (config.systemPrompt != null) {
            builder.systemPrompt(config.systemPrompt);
        }
        if (config.model != null) {
            builder.model(config.model);
        }
        if (config.maxTurns != null) {
            builder.maxTurn(config.maxTurns);
        }


        return builder.build();
    }

    private SessionConfig toSessionConfig(AgentDefinition definition) {
        var config = new SessionConfig();
        var source = definition.publishedConfig != null ? definition.publishedConfig : null;
        var systemPromptId = source != null && source.systemPromptId != null ? source.systemPromptId : definition.systemPromptId;
        var inlineSystemPrompt = source != null && source.systemPrompt != null ? source.systemPrompt : definition.systemPrompt;
        config.systemPrompt = systemPromptId != null ? systemPromptService.resolveContent(systemPromptId) : inlineSystemPrompt;
        config.model = source != null && source.model != null ? source.model : definition.model;
        config.temperature = source != null && source.temperature != null ? source.temperature : definition.temperature;
        config.maxTurns = source != null && source.maxTurns != null ? source.maxTurns : definition.maxTurns;
        return config;
    }

    private Agent buildAgent(SessionConfig config, List<ToolCall> tools, ExecutionContext context, String agentName) {
        var builder = Agent.builder()
                .name(agentName != null ? agentName.replaceAll("\\s+", "-") : "assistant")
                .llmProvider(llmProviders.getProvider())
                .toolCalls(tools != null ? tools : BuiltinTools.ALL)
                .temperature(config != null && config.temperature != null ? config.temperature : 0.8);

        if (config != null && config.systemPrompt != null) {
            builder.systemPrompt(config.systemPrompt);
        } else {
            builder.systemPrompt("You are a helpful AI assistant.");
        }
        if (config != null && config.model != null) {
            builder.model(config.model);
        }
        if (config != null && config.maxTurns != null) {
            builder.maxTurn(config.maxTurns);
        }

        if (context != null) {
            builder.executionContext(context);
        }


        var provider = persistenceProviders.getDefaultPersistenceProvider();
        if (provider != null) {
            builder.persistenceProvider(provider);
        }

        return builder.build();
    }

    public record SessionCreationResult(String sessionId, List<String> loadedSubAgents, List<String> loadedSkills) {
    }
}
