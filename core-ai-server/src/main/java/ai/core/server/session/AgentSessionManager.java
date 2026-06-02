package ai.core.server.session;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProviders;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DeleteDatasetRecordTool;
import ai.core.server.dataset.tool.InsertDatasetRecordTool;
import ai.core.server.dataset.tool.QueryDatasetRecordsTool;
import ai.core.server.dataset.tool.UpdateDatasetRecordTool;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.server.web.sse.SseEventBridge;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class AgentSessionManager {
    private final Logger logger = LoggerFactory.getLogger(AgentSessionManager.class);
    private final ConcurrentMap<String, InProcessAgentSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> sessionLastActivity = new ConcurrentHashMap<>();

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
    @Inject
    ChatArtifactSetup artifactSetup;
    @Inject
    DatasetService datasetService;
    @Inject
    DatasetRecordService datasetRecordService;

    EventPublisher eventPublisher;
    SessionOwnershipRegistry ownershipRegistry;

    private SessionSkillManager skillManager;
    private SessionSubAgentManager subAgentManager;
    private SessionRebuildManager rebuildManager;

    public void setEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void setOwnershipRegistry(SessionOwnershipRegistry ownershipRegistry) {
        this.ownershipRegistry = ownershipRegistry;
    }

    private SessionSkillManager skillManager() {
        if (skillManager == null) {
            skillManager = new SessionSkillManager(skillService, mongoSkillProvider, skillArchiveBuilder, chatMessageService);
        }
        return skillManager;
    }

    private SessionSubAgentManager subAgentManager() {
        if (subAgentManager == null) {
            subAgentManager = new SessionSubAgentManager(agentDefinitionCollection, chatMessageService, toolRegistryService,
                    systemPromptService, llmProviders, persistenceProviders);
        }
        return subAgentManager;
    }

    private SessionRebuildManager rebuildManager() {
        if (rebuildManager == null) {
            rebuildManager = new SessionRebuildManager(new SessionRebuildManager.Deps(chatMessageService, agentDefinitionCollection,
                    skillManager(), subAgentManager(), sandboxService, artifactSetup,
                    toolRegistryService, systemPromptService, datasetService, datasetRecordService, eventPublisher, ownershipRegistry));
        }
        return rebuildManager;
    }

    private void attachSessionListeners(InProcessAgentSession session, String sessionId) {
        session.onEvent(chatMessageService.listener(sessionId));
        session.onEvent(new SseEventBridge(sessionId, eventPublisher));
    }

    public void touchActivity(String sessionId) {
        sessionLastActivity.put(sessionId, System.currentTimeMillis());
        sandboxService.renewSandbox(sessionId);
    }

    public String createSession(SessionConfig config, String userId) {
        return createSession(config, userId, "chat");
    }

    public String createSession(SessionConfig config, String userId, String source) {
        var effectiveConfig = config != null ? config : new SessionConfig();
        var sessionId = UUID.randomUUID().toString();
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).build();
        var sandboxOn = sandboxService.isSandboxEnabled(null);
        var tools = buildDefaultSessionTools(effectiveConfig, sessionId);
        Map<String, Object> extraVars = null;
        if (hasText(effectiveConfig.datasetId)) {
            effectiveConfig.systemPrompt = appendDatasetInstructions(effectiveConfig.systemPrompt);
            extraVars = buildDatasetSystemVars(effectiveConfig.datasetId);
        }
        var agent = subAgentManager().buildAgent(artifactSetup.appendArtifactInstructions(effectiveConfig, sandboxOn),
                artifactSetup.withSubmitArtifactsTool(tools, sessionId, userId, sandboxOn), context, null, extraVars);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        session.setOnIdle(() -> renewSessionOwnership(sessionId));
        attachSessionListeners(session, sessionId);
        chatMessageService.registerSession(sessionId, ChatMessageService.SessionMeta.of(userId, null, source));
        var sandbox = sandboxService.createSandbox(null, sessionId, userId, session::dispatchEvent);
        if (sandbox != null) context.sandbox(sandbox);
        sessions.put(sessionId, session);
        touchActivity(sessionId);
        claimOwnership(sessionId);
        return sessionId;
    }

    public SessionCreationResult createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId) {
        return createSessionFromAgent(definition, overrides, userId, "chat");
    }

    public SessionCreationResult createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId, String source) {
        var config = subAgentManager().toSessionConfig(definition);
        if (overrides != null) {
            if (overrides.model != null) config.model = overrides.model;
            if (overrides.multiModalModel != null) config.multiModalModel = overrides.multiModalModel;
            if (overrides.temperature != null) config.temperature = overrides.temperature;
            if (overrides.systemPrompt != null) config.systemPrompt = overrides.systemPrompt;
            if (overrides.maxTurns != null) config.maxTurns = overrides.maxTurns;
        }
        var tools = subAgentManager().resolveTools(definition);
        var sessionId = UUID.randomUUID().toString();
        var agentDatasetId = definition.publishedConfig != null ? definition.publishedConfig.outputDatasetId : definition.outputDatasetId;
        var datasetId = overrides != null && hasText(overrides.datasetId) ? overrides.datasetId : agentDatasetId;
        config.datasetId = datasetId;
        tools = addDatasetTools(tools, datasetId, definition.id, sessionId);
        Map<String, Object> extraVars = null;
        if (hasText(datasetId)) {
            config.systemPrompt = appendDatasetInstructions(config.systemPrompt);
            extraVars = buildDatasetSystemVars(datasetId);
        }
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId).build();
        var sandboxConfig = sandboxService.getEffectiveConfig(definition);
        var sandboxOn = sandboxService.isSandboxEnabled(sandboxConfig);
        var agent = subAgentManager().buildAgent(artifactSetup.appendArtifactInstructions(config, sandboxOn),
                artifactSetup.withSubmitArtifactsTool(tools.isEmpty() ? null : tools, sessionId, userId, sandboxOn),
                context, definition.name, extraVars);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        session.setOnIdle(() -> renewSessionOwnership(sessionId));
        attachSessionListeners(session, sessionId);
        chatMessageService.registerSession(sessionId, ChatMessageService.SessionMeta.of(userId, definition.id, source));
        var sandbox2 = sandboxService.createSandbox(sandboxConfig, sessionId, userId, session::dispatchEvent);
        if (sandbox2 != null) context.sandbox(sandbox2);
        sessions.put(sessionId, session);
        touchActivity(sessionId);
        claimOwnership(sessionId);
        var loadedSkillIds = definition.publishedConfig != null && definition.publishedConfig.skillIds != null
                ? definition.publishedConfig.skillIds
                : definition.skillIds;
        var loadedSubAgentIds = definition.publishedConfig != null && definition.publishedConfig.subAgentIds != null
                ? definition.publishedConfig.subAgentIds
                : definition.subAgentIds;
        skillManager().loadSkillsFromDefinition(session, definition);
        subAgentManager().loadSubAgentsFromDefinition(session, definition);
        return new SessionCreationResult(sessionId,
                loadedSubAgentIds != null ? loadedSubAgentIds : List.of(),
                loadedSkillIds != null ? loadedSkillIds : List.of());
    }

    private void claimOwnership(String sessionId) {
        if (ownershipRegistry != null) {
            ownershipRegistry.claim(sessionId);
        }
    }

    private void renewSessionOwnership(String sessionId) {
        if (ownershipRegistry != null) {
            ownershipRegistry.claimOrRenew(sessionId);
        }
    }

    public InProcessAgentSession getSession(String sessionId) {
        return getSession(sessionId, null);
    }

    public InProcessAgentSession getSession(String sessionId, SessionState state) {
        var session = sessions.get(sessionId);
        if (session != null) {
            touchActivity(sessionId);
            return session;
        }
        if (ownershipRegistry != null) {
            var owner = ownershipRegistry.getOwner(sessionId);
            if (owner != null && !ownershipRegistry.isOwner(sessionId)) {
                logger.info("session owned by another pod, not rebuilding locally, sessionId={}, owner={}", sessionId, owner);
                throw new NotFoundException("session not found locally, sessionId=" + sessionId + ", owner=" + owner);
            }
        }
        var effectiveState = state != null ? state : rebuildManager().buildStateFromDb(sessionId);
        if (effectiveState == null) {
            throw new NotFoundException("session not found, sessionId=" + sessionId);
        }
        var built = sessions.computeIfAbsent(sessionId, id -> {
            logger.info("session not found locally, attempting to rebuild, sessionId={}", id);
            var rebuilt = rebuildManager().rebuildSession(id, effectiveState);
            if (rebuilt != null) {
                claimOwnership(id);
                logger.info("session rebuilt successfully, sessionId={}", id);
            }
            return rebuilt;
        });
        if (built == null) {
            throw new NotFoundException("session not found, sessionId=" + sessionId);
        }
        touchActivity(sessionId);
        return built;
    }

    public void touchSession(String sessionId) {
        if (ownershipRegistry != null) {
            ownershipRegistry.claimOrRenew(sessionId);
        }
    }

    public void closeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) session.close();
        skillManager().removeSkillState(sessionId);
        sessionLastActivity.remove(sessionId);
        sandboxService.releaseSandbox(sessionId);
        chatMessageService.onSessionClosed(sessionId);
        sessionChannelService.close(sessionId);
        if (ownershipRegistry != null) {
            ownershipRegistry.release(sessionId);
        }
    }

    public int cleanupIdleSessions(Duration maxIdle) {
        var threshold = System.currentTimeMillis() - maxIdle.toMillis();
        var closed = 0;
        for (var entry : sessionLastActivity.entrySet()) {
            var sessionId = entry.getKey();
            if (entry.getValue() >= threshold) continue;
            if (ownershipRegistry != null && !ownershipRegistry.isOwner(sessionId)) {
                sessionLastActivity.remove(sessionId);
                continue;
            }
            try {
                logger.info("closing idle session, sessionId={}, idleMs={}", sessionId, System.currentTimeMillis() - entry.getValue());
                closeSession(sessionId);
                closed++;
            } catch (Exception e) {
                logger.warn("failed to close idle session, sessionId={}", sessionId, e);
            }
        }
        return closed;
    }

    public List<String> loadToolRefs(String sessionId, List<ToolRef> toolRefs) {
        var session = getSession(sessionId);
        var tools = toolRegistryService.resolveToolRefs(toolRefs);
        if (tools.isEmpty()) {
            throw new NotFoundException("no tools found for refs: " + toolRefs);
        }
        session.loadTools(tools);
        chatMessageService.addLoadedTools(sessionId, toolRefs);
        return tools.stream().map(ToolCall::getName).toList();
    }

    public List<String> unloadSkills(String sessionId, List<String> skillIds) {
        return skillManager().unloadSkills(sessionId, skillIds);
    }

    public List<String> loadSkills(String sessionId, List<String> skillIds) {
        var session = getSession(sessionId);
        return skillManager().loadSkills(session, skillIds);
    }

    public List<String> loadSubAgents(String sessionId, List<AgentDefinition> definitions) {
        var session = getSession(sessionId);
        return subAgentManager().loadSubAgents(session, definitions);
    }

    private List<ToolCall> addDatasetTools(List<ToolCall> tools, String datasetId, String agentId, String sessionId) {
        if (!hasText(datasetId)) return tools;
        var dataset = datasetService.get(datasetId);
        if (dataset == null) return tools;
        var mutable = new ArrayList<>(tools);
        mutable.add(QueryDatasetRecordsTool.create(datasetId, datasetRecordService, dataset));
        mutable.add(InsertDatasetRecordTool.create(datasetId, agentId, sessionId, datasetRecordService, dataset));
        mutable.add(UpdateDatasetRecordTool.create(datasetId, datasetRecordService, dataset));
        mutable.add(DeleteDatasetRecordTool.create(datasetId, datasetRecordService, dataset));
        return mutable;
    }

    private String appendDatasetInstructions(String systemPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) return Prompts.DATASET_SYSTEM_PROMPT.strip();
        return systemPrompt + Prompts.DATASET_SYSTEM_PROMPT;
    }

    private Map<String, Object> buildDatasetSystemVars(String datasetId) {
        var dataset = datasetService.get(datasetId);
        if (dataset == null) return null;
        var vars = new HashMap<String, Object>();
        vars.put(SystemVariables.AGENT_DATASET_NAME, dataset.name);
        vars.put(SystemVariables.AGENT_DATASET_DESC, dataset.description != null && !dataset.description.isBlank() ? ": " + dataset.description : "");
        return vars;
    }

    private List<ToolCall> buildDefaultSessionTools(SessionConfig config, String sessionId) {
        if (config == null || !hasText(config.datasetId)) return null;
        var dataset = datasetService.get(config.datasetId);
        if (dataset == null) return null;
        var tools = new ArrayList<ToolCall>(BuiltinTools.ALL);
        tools.add(QueryDatasetRecordsTool.create(config.datasetId, datasetRecordService, dataset));
        tools.add(InsertDatasetRecordTool.create(config.datasetId, "default", sessionId, datasetRecordService, dataset));
        tools.add(UpdateDatasetRecordTool.create(config.datasetId, datasetRecordService, dataset));
        tools.add(DeleteDatasetRecordTool.create(config.datasetId, datasetRecordService, dataset));
        return tools;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record SessionCreationResult(String sessionId, List<String> loadedSubAgentIds, List<String> loadedSkillIds) {
    }
}
