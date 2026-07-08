package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.SessionConfig;
import ai.core.prompt.Prompts;
import ai.core.prompt.SystemVariables;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.dataset.tool.DatasetAccessRegistry;
import ai.core.server.dataset.tool.DeleteDatasetRecordTool;
import ai.core.server.dataset.tool.InsertDatasetRecordTool;
import ai.core.server.dataset.tool.QueryDatasetRecordsTool;
import ai.core.server.dataset.tool.UpdateDatasetRecordTool;
import ai.core.server.file.FileDownloadUrlResolver;
import ai.core.server.file.FileService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.run.SubmitArtifactsTool;
import ai.core.server.sandbox.SandboxLifecycle;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.server.web.sse.SseEventBridge;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.InternalUrlResolver;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    private final FileService fileService;
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
        this.fileService = deps.fileService;
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
        var defaultSubAgentIds = definition.publishedConfig != null && definition.publishedConfig.subAgentIds != null
                ? definition.publishedConfig.subAgentIds
                : definition.subAgentIds;
        state.subAgentIds = IdLists.clean(defaultSubAgentIds);
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
            state.subAgentIds = mergeIds(state.subAgentIds, meta.loadedSubAgentIds);
        }
    }

    private List<String> mergeIds(List<String> first, List<String> second) {
        var merged = new LinkedHashSet<String>();
        merged.addAll(IdLists.clean(first));
        merged.addAll(IdLists.clean(second));
        return new ArrayList<>(merged);
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
        snapshot.datasetConfig = pub != null && pub.datasetConfig != null ? pub.datasetConfig : def.datasetConfig;
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
        List<AgentDatasetConfig> datasetConfig;
        if (state != null && state.config != null && state.config.datasetConfigs != null && !state.config.datasetConfigs.isEmpty()) {
            // Session-level override: use stored datasetConfigs
            datasetConfig = state.config.datasetConfigs.stream().map(entry -> {
                var dc = new AgentDatasetConfig();
                dc.datasetId = entry.datasetId;
                dc.permission = DatasetPermission.valueOf(entry.permission);
                dc.isOutput = entry.isOutput;
                return dc;
            }).toList();
            config.datasetConfigs = state.config.datasetConfigs;
        } else if (state != null && state.config != null && hasText(state.config.datasetId)) {
            // Backward compat: single datasetId override
            config.datasetId = state.config.datasetId;
            datasetConfig = List.of(createConfig(state.config.datasetId, DatasetPermission.READ));
        } else {
            config.datasetId = findOutputDatasetId(snapshot.datasetConfig);
            datasetConfig = snapshot.datasetConfig;
        }
        return doRebuild(sessionId, config, snapshot.tools, userId, snapshot.agentName, state, snapshot.agentId, datasetConfig, snapshot.variables);
    }

    private InProcessAgentSession rebuildFromConfig(String sessionId, SessionConfig config, String userId, SessionState state) {
        return doRebuild(sessionId, config, null, userId, null, state, "default", null, null);
    }

    private InProcessAgentSession doRebuild(String sessionId, SessionConfig config, List<ai.core.server.domain.ToolRef> toolRefs, String userId, String agentName,
                                             SessionState state, String datasetAgentId, List<AgentDatasetConfig> datasetConfig, Map<String, String> configVars) {
        var start = System.currentTimeMillis();
        var agentId = state != null && state.fromAgent && state.agentConfig != null ? state.agentConfig.agentId : null;
        var effectiveConfig = config != null ? config : new SessionConfig();
        var context = userId != null ? ExecutionContext.builder().sessionId(sessionId).userId(userId)
                .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, SubmitArtifactsTool.publicUrl))
                .build() : null;
        var sandboxOn = context != null && sandboxService.isSandboxEnabled(null);

        // Sandbox must exist before resolveToolRefs so sandbox-hosted MCP refs see
        // a session sandbox. The session itself is created after the agent — wire
        // the event dispatcher through a holder.
        var sessionRef = new InProcessAgentSession[1];
        if (context != null) {
            var sandbox = sandboxService.createSessionSandbox(null, sessionId, userId,
                    event -> { if (sessionRef[0] != null) sessionRef[0].dispatchEvent(event); });
            if (sandbox != null) context.sandbox(sandbox);
        }

        List<ToolCall> tools = (toolRefs != null && !toolRefs.isEmpty())
                ? toolRegistryService.resolveToolRefs(toolRefs, sessionId)
                : new ArrayList<>();
        tools = addDatasetTools(tools, datasetConfig, agentId, sessionId);
        Map<String, Object> extraVars = null;
        if (datasetConfig != null && !datasetConfig.isEmpty()) {
            effectiveConfig.systemPrompt = appendDatasetInstructions(effectiveConfig.systemPrompt, datasetConfig);
            extraVars = buildDatasetSystemVars(datasetConfig);
        }
        // Inject the agent's configured variables so the system prompt template renders correctly
        // on rebuild (history is restored as user/assistant only, so the system message is rebuilt here).
        // Skip null keys/values: they end up in a ConcurrentHashMap during rendering, which rejects nulls.
        if (configVars != null && !configVars.isEmpty()) {
            if (extraVars == null) extraVars = new HashMap<>();
            for (var entry : configVars.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) extraVars.put(entry.getKey(), entry.getValue());
            }
        }
        var toolCount = tools != null ? tools.size() : 0;
        var skillCount = state != null && state.skillIds != null ? state.skillIds.size() : 0;
        var subAgentCount = state != null && state.subAgentIds != null ? state.subAgentIds.size() : 0;
        var dynamicToolCount = state != null && state.tools != null ? state.tools.size() : 0;
        logger.info("doRebuild start, sessionId={}, fromAgent={}, baseTools={}, dynamicTools={}, skills={}, subAgents={}",
                sessionId, state != null && state.fromAgent, toolCount, dynamicToolCount, skillCount, subAgentCount);
        var agent = subAgentManager.buildAgent(effectiveConfig,
                SessionSubAgentManager.toolsToRegistry(tools),
                context, agentName, extraVars, agentId,
                sandboxOn ? List.of(new SandboxLifecycle(fileService, artifactSetup.createChatSessionSink(sessionId))) : null,
                null);
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        sessionRef[0] = session;
        session.setOnIdle(() -> renewSessionOwnership(sessionId));
        session.onEvent(chatMessageService.listener(sessionId));
        if (eventPublisher != null) {
            session.onEvent(new SseEventBridge(sessionId, eventPublisher));
        }
        registerSessionFromDb(sessionId, userId);
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
                var resolved = toolRegistryService.resolveToolRefs(state.tools, sessionId);
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

    private List<ToolCall> addDatasetTools(List<ToolCall> tools, List<AgentDatasetConfig> datasetConfig, String agentId, String sessionId) {
        if (datasetConfig == null || datasetConfig.isEmpty()) return tools;
        var registry = DatasetAccessRegistry.from(datasetConfig);
        var mutable = new ArrayList<ToolCall>(tools != null ? tools : List.of());
        var effectiveAgentId = hasText(agentId) ? agentId : "default";
        mutable.add(QueryDatasetRecordsTool.create(datasetService, datasetRecordService, registry));
        if (registry.hasAnyWrite()) {
            mutable.add(InsertDatasetRecordTool.create(effectiveAgentId, sessionId, datasetService, datasetRecordService, registry));
            mutable.add(UpdateDatasetRecordTool.create(datasetService, datasetRecordService, registry));
        }
        if (registry.hasAnyFull()) {
            mutable.add(DeleteDatasetRecordTool.create(datasetService, datasetRecordService, registry));
        }
        return mutable;
    }

    private String appendDatasetInstructions(String systemPrompt, List<AgentDatasetConfig> datasetConfig) {
        if (systemPrompt == null || systemPrompt.isBlank()) return Prompts.DATASET_SYSTEM_PROMPT.strip();
        return systemPrompt + Prompts.DATASET_SYSTEM_PROMPT;
    }

    private Map<String, Object> buildDatasetSystemVars(List<AgentDatasetConfig> datasetConfig) {
        if (datasetConfig == null || datasetConfig.isEmpty()) return null;
        var names = new ArrayList<String>();
        var desc = new StringBuilder();
        for (var cfg : datasetConfig) {
            var dataset = datasetService.get(cfg.datasetId);
            if (dataset == null) continue;
            names.add(dataset.name);
            desc.append("\n- \"").append(dataset.name).append("\" (").append(cfg.permission.name()).append(")");
            if (dataset.description != null && !dataset.description.isBlank()) {
                desc.append(": ").append(dataset.description);
            }
        }
        var vars = new HashMap<String, Object>();
        vars.put(SystemVariables.AGENT_DATASET_NAME, String.join(", ", names));
        vars.put(SystemVariables.AGENT_DATASET_DESC, desc.toString());
        return vars;
    }

    private AgentDatasetConfig createConfig(String datasetId, DatasetPermission permission) {
        var c = new AgentDatasetConfig();
        c.datasetId = datasetId;
        c.permission = permission;
        return c;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String findOutputDatasetId(List<AgentDatasetConfig> configs) {
        if (configs == null) return null;
        return configs.stream()
                .filter(c -> c.isOutput != null && c.isOutput)
                .findFirst()
                .map(c -> c.datasetId)
                .orElse(null);
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
                        FileService fileService,
                        EventPublisher eventPublisher,
                        SessionOwnershipRegistry ownershipRegistry) { }
}
