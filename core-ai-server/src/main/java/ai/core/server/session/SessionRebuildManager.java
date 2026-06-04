package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DeleteDatasetRecordTool;
import ai.core.server.dataset.tool.InsertDatasetRecordTool;
import ai.core.server.dataset.tool.QueryDatasetRecordsTool;
import ai.core.server.dataset.tool.UpdateDatasetRecordTool;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.server.web.sse.SseEventBridge;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCall;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final DatasetService datasetService;
    private final DatasetRecordService datasetRecordService;
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
        this.datasetService = deps.datasetService;
        this.datasetRecordService = deps.datasetRecordService;
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
            state.skillIds = IdLists.clean(meta.loadedSkillIds);
        }
        if (meta.loadedSubAgentIds != null && !meta.loadedSubAgentIds.isEmpty()) {
            state.subAgentIds = IdLists.clean(meta.loadedSubAgentIds);
        }
    }

    private SessionState.AgentConfigSnapshot buildSnapshotFromDefinition(AgentDefinition def) {
        var pub = def.publishedConfig;
        var snapshot = new SessionState.AgentConfigSnapshot();
        snapshot.agentId = def.id;
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
        snapshot.outputDatasetId = pub != null && pub.outputDatasetId != null ? pub.outputDatasetId : def.outputDatasetId;
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
        config.datasetId = state != null && state.config != null && hasText(state.config.datasetId)
                ? state.config.datasetId
                : snapshot.outputDatasetId;
        List<ToolCall> tools = (snapshot.tools != null && !snapshot.tools.isEmpty()) ? toolRegistryService.resolveToolRefs(snapshot.tools) : List.of();
        return doRebuild(sessionId, config, tools, userId, snapshot.agentName, state, snapshot.agentId);
    }

    private InProcessAgentSession rebuildFromConfig(String sessionId, SessionConfig config, String userId, SessionState state) {
        return doRebuild(sessionId, config, null, userId, null, state, "default");
    }

    private InProcessAgentSession doRebuild(String sessionId, SessionConfig config, List<ToolCall> tools, String userId, String agentName,
                                            SessionState state, String datasetAgentId) {
        var start = System.currentTimeMillis();
        var effectiveConfig = config != null ? config : new SessionConfig();
        tools = addDatasetTools(tools, effectiveConfig.datasetId, datasetAgentId, sessionId);
        Map<String, Object> extraVars = null;
        if (hasText(effectiveConfig.datasetId)) {
            effectiveConfig.systemPrompt = appendDatasetInstructions(effectiveConfig.systemPrompt);
            extraVars = buildDatasetSystemVars(effectiveConfig.datasetId);
        }
        var toolCount = tools != null ? tools.size() : 0;
        var skillCount = state != null && state.skillIds != null ? state.skillIds.size() : 0;
        var subAgentCount = state != null && state.subAgentIds != null ? state.subAgentIds.size() : 0;
        var dynamicToolCount = state != null && state.tools != null ? state.tools.size() : 0;
        logger.info("doRebuild start, sessionId={}, fromAgent={}, baseTools={}, dynamicTools={}, skills={}, subAgents={}",
                sessionId, state != null && state.fromAgent, toolCount, dynamicToolCount, skillCount, subAgentCount);
        var context = userId != null ? ExecutionContext.builder().sessionId(sessionId).userId(userId).build() : null;
        var sandboxOn = context != null && sandboxService.isSandboxEnabled(null);
        var agent = subAgentManager.buildAgent(artifactSetup.appendArtifactInstructions(effectiveConfig, sandboxOn),
                sandboxOn ? artifactSetup.withSubmitArtifactsTool(tools, sessionId, userId, true) : tools,
                context, agentName, extraVars);
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
                var definitions = IdLists.clean(state.subAgentIds).stream()
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

    private List<ToolCall> addDatasetTools(List<ToolCall> tools, String datasetId, String agentId, String sessionId) {
        if (!hasText(datasetId)) return tools;
        var dataset = datasetService.get(datasetId);
        if (dataset == null) return tools;
        var mutable = new ArrayList<ToolCall>(tools != null ? tools : List.of());
        var effectiveAgentId = hasText(agentId) ? agentId : "default";
        mutable.add(QueryDatasetRecordsTool.create(datasetId, datasetRecordService, dataset));
        mutable.add(InsertDatasetRecordTool.create(datasetId, effectiveAgentId, sessionId, datasetRecordService, dataset));
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record Deps(ChatMessageService chatMessageService,
                        MongoCollection<AgentDefinition> agentDefinitionCollection,
                        SessionSkillManager skillManager,
                        SessionSubAgentManager subAgentManager,
                        SandboxService sandboxService,
                        ChatArtifactSetup artifactSetup,
                        ToolRegistryService toolRegistryService,
                        SystemPromptService systemPromptService,
                        DatasetService datasetService,
                        DatasetRecordService datasetRecordService,
                        EventPublisher eventPublisher,
                        SessionOwnershipRegistry ownershipRegistry) { }
}
