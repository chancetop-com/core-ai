package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.media.MediaProvider;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.file.FileService;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.User;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.sandbox.SandboxLifecycle;
import ai.core.server.sandbox.SandboxService;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.CallerContexts;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
/**
 * @author stephen
 */
public class SessionRebuildManager {
    private static String normalizeThinkingEffort(String value) {
        var effort = ReasoningEffort.fromString(value);
        return effort != null ? effort.name().toLowerCase(java.util.Locale.ROOT) : null;
    }

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
    private final PublicUrlConfiguration publicUrlConfiguration;
    private final EventPublisher eventPublisher;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final SystemSettingsService systemSettingsService;
    private final MongoCollection<User> userCollection;
    private final SessionContextBuilder contextBuilder;
    private final TurnStateRegistry turnStateRegistry;
    private SessionDatasetHelper datasetHelper;

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
        this.publicUrlConfiguration = deps.publicUrlConfiguration;
        this.eventPublisher = deps.eventPublisher;
        this.ownershipRegistry = deps.ownershipRegistry;
        this.systemSettingsService = deps.systemSettingsService;
        this.userCollection = deps.userCollection;
        this.turnStateRegistry = deps.turnStateRegistry;
        this.contextBuilder = new SessionContextBuilder(artifactSetup, fileService, publicUrlConfiguration,
                systemSettingsService, deps.mediaProvider, deps.quotaService).withAsyncTaskManager(deps.asyncTaskManager);
    }

    private SessionDatasetHelper datasetHelper() {
        if (datasetHelper == null) datasetHelper = new SessionDatasetHelper(datasetService, datasetRecordService);
        return datasetHelper;
    }

    public SessionState buildStateFromDb(String sessionId) {
        var meta = chatMessageService.getSessionMeta(sessionId);
        if (meta == null) return null;
        var state = new SessionState();
        state.agentSnapshotSecurityVersion = SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION;
        state.sessionId = sessionId;
        state.userId = meta.userId;
        if (meta.agentId == null) {
            state.fromAgent = false;
            populateDynamicLoads(state, meta);
            return state;
        }
        var definition = agentDefinitionCollection.get(meta.agentId).orElse(null);
        if (definition == null) {
            throw new IllegalArgumentException("agent is unavailable");
        }
        var ownedEditable = AgentDependencyAccessPolicy.isOwnedEditable(definition, meta.userId);
        definition = AgentDependencyAccessPolicy.executableSessionAgent(definition, meta.userId);
        if (ownedEditable) skillManager.resolveAccessibleDefinitionSkills(definition, meta.userId);
        state.fromAgent = true;
        state.agentConfig = SessionAgentSnapshotSecurity.buildSnapshot(definition);
        var defaultSubAgentIds = definition.publishedConfig != null
                ? definition.publishedConfig.subAgentIds : definition.subAgentIds;
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
            var definitionSkillIds = new LinkedHashSet<>(IdLists.clean(
                    state.agentConfig != null ? state.agentConfig.skillIds : null));
            state.skillIds = IdLists.clean(meta.loadedSkillIds).stream()
                    .filter(id -> !definitionSkillIds.contains(id))
                    .toList();
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
    public InProcessAgentSession rebuildSession(String sessionId, SessionState state) {
        return rebuildSession(sessionId, state, null);
    }
    public InProcessAgentSession rebuildSession(String sessionId, SessionState state, String callerUserId) {
        if (state.fromAgent && state.agentConfig == null) return null;
        try {
            if (state.fromAgent && state.agentConfig != null && SessionAgentSnapshotSecurity.isTrusted(state)) {
                var trustedUserId = SessionAgentSnapshotSecurity.trustedUserId(state, callerUserId);
                var allowReattach = SessionAgentSnapshotSecurity.isSandboxBindingTrusted(state);
                if (!allowReattach) sandboxService.invalidateSandboxBinding(sessionId);
                return rebuildFromSnapshot(sessionId, state.agentConfig, trustedUserId, state, allowReattach);
            }
            if (state.fromAgent && state.agentConfig != null) {
                var identity = SessionAgentSnapshotSecurity.authorizeLegacyIdentity(sessionId, state,
                        callerUserId, chatMessageService);
                sandboxService.invalidateSandboxBinding(sessionId);
                var safeState = SessionAgentSnapshotSecurity.rederiveLegacyState(sessionId, identity,
                        agentDefinitionCollection, skillManager);
                return rebuildFromSnapshot(sessionId, safeState.agentConfig, safeState.userId, safeState, false);
            }
            return rebuildFromConfig(sessionId, state.config, state.userId, state);
        } catch (Exception e) {
            logger.warn("failed to rebuild session, sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }
    private InProcessAgentSession rebuildFromSnapshot(String sessionId, SessionState.AgentConfigSnapshot snapshot,
                                                      String userId, SessionState state,
                                                      boolean allowSandboxReattach) {
        var config = new SessionConfig();
        config.systemPrompt = snapshot.systemPromptId != null ? systemPromptService.resolveContent(snapshot.systemPromptId) : snapshot.systemPrompt;
        config.model = snapshot.model;
        config.multiModalModel = snapshot.multiModalModel;
        config.preferCaptionPath = snapshot.preferCaptionPath;
        config.temperature = snapshot.temperature;
        config.reasoningEffort = normalizeThinkingEffort(snapshot.thinkingEffort);
        config.maxTurns = snapshot.maxTurns;
        List<AgentDatasetConfig> datasetConfig;
        if (state != null && state.config != null && state.config.datasetConfigs != null && !state.config.datasetConfigs.isEmpty()) {
            datasetConfig = state.config.datasetConfigs.stream().map(entry -> {
                var dc = new AgentDatasetConfig();
                dc.datasetId = entry.datasetId;
                dc.permission = DatasetPermission.valueOf(entry.permission);
                dc.isOutput = entry.isOutput;
                return dc;
            }).toList();
            config.datasetConfigs = state.config.datasetConfigs;
        } else if (state != null && state.config != null && hasText(state.config.datasetId)) {
            config.datasetId = state.config.datasetId;
            datasetConfig = List.of(createConfig(state.config.datasetId, DatasetPermission.READ));
        } else {
            config.datasetId = findOutputDatasetId(snapshot.datasetConfig);
            datasetConfig = snapshot.datasetConfig;
        }
        var sandboxConfig = snapshot.sandboxConfig != null ? snapshot.sandboxConfig.toConfig() : null;
        var params = new RebuildParams(sessionId, config, snapshot.tools, userId, snapshot.agentName, state,
                snapshot.agentId, datasetConfig, snapshot.variables, sandboxConfig, allowSandboxReattach);
        return doRebuild(params);
    }
    private InProcessAgentSession rebuildFromConfig(String sessionId, SessionConfig config, String userId, SessionState state) {
        return doRebuild(new RebuildParams(sessionId, config, null, userId, null, state,
                "default", null, null, null, true));
    }
    private SandboxSetup setupSandboxContext(String sessionId, String userId, SandboxConfig sandboxConfig,
                                             boolean allowSandboxReattach) {
        var context = userId != null ? contextBuilder.build(sessionId, userId) : null;
        if (context != null) {
            context.setCaller(CallerContexts.fromUser(userCollection.get(userId).orElse(null)));
        }
        var sandboxOn = context != null && sandboxService.isSandboxEnabled(sandboxConfig);
        var sessionRef = new InProcessAgentSession[1];
        if (context != null) {
            var sandbox = createOrReattachSandbox(sessionId, userId, sandboxConfig, sessionRef,
                    allowSandboxReattach);
            if (sandbox != null) context.sandbox(sandbox);
        }
        return new SandboxSetup(context, sessionRef, sandboxOn);
    }
    private Sandbox createOrReattachSandbox(String sessionId, String userId, SandboxConfig sandboxConfig,
                                            InProcessAgentSession[] sessionRef,
                                            boolean allowSandboxReattach) {
        var sandbox = allowSandboxReattach
                ? reattachExistingSandbox(sessionId, userId, sandboxConfig, sessionRef) : null;
        if (sandbox == null) {
            sandbox = sandboxService.createSessionSandbox(sandboxConfig, sessionId, userId,
                    event -> {
                        if (sessionRef[0] != null) sessionRef[0].dispatchEvent(event);
                    });
        }
        return sandbox;
    }
    private Sandbox reattachExistingSandbox(String sessionId, String userId, SandboxConfig sandboxConfig,
                                            InProcessAgentSession[] sessionRef) {
        var existingSandboxId = sandboxService.getSandboxId(sessionId);
        if (existingSandboxId == null) return null;
        var sandbox = sandboxService.reattachOrCreateSandbox(existingSandboxId, sandboxConfig, sessionId, userId,
                event -> {
                    if (sessionRef[0] != null) sessionRef[0].dispatchEvent(event);
                });
        if (sandbox != null) {
            logger.info("reattached to existing sandbox during rebuild, sessionId={}, sandboxId={}", sessionId, existingSandboxId);
        }
        return sandbox;
    }
    private Map<String, Object> injectConfigVars(Map<String, Object> extraVars, Map<String, String> configVars) {
        if (configVars == null || configVars.isEmpty()) return extraVars;
        var result = extraVars != null ? extraVars : new HashMap<String, Object>();
        for (var entry : configVars.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }
    private InProcessAgentSession doRebuild(RebuildParams params) {
        var start = System.currentTimeMillis();
        var agentId = params.state != null && params.state.fromAgent && params.state.agentConfig != null ? params.state.agentConfig.agentId : null;
        var effectiveConfig = params.config != null ? params.config : new SessionConfig();
        var sandbox = setupSandboxContext(params.sessionId, params.userId, params.sandboxConfig,
                params.allowSandboxReattach);
        List<ToolCall> tools = (params.toolRefs != null && !params.toolRefs.isEmpty())
                ? toolRegistryService.resolveToolRefs(params.toolRefs, params.sessionId, params.userId)
                : new ArrayList<>();
        var toolRegistry = SessionSubAgentManager.toolsToRegistry(tools);
        datasetHelper().addDatasetToolsToRegistry(toolRegistry, params.datasetConfig,
                hasText(agentId) ? agentId : "default", params.sessionId);
        Map<String, Object> extraVars = null;
        if (params.datasetConfig != null && !params.datasetConfig.isEmpty()) {
            effectiveConfig.systemPrompt = datasetHelper().appendDatasetInstructions(effectiveConfig.systemPrompt, params.datasetConfig);
            extraVars = datasetHelper().buildDatasetSystemVars(params.datasetConfig);
        }
        extraVars = injectConfigVars(extraVars, params.configVars);
        logRebuildStart(params, tools);
        var agent = subAgentManager.buildAgent(new SessionSubAgentManager.BuildAgentParams(
                effectiveConfig, toolRegistry,
                sandbox.context, params.agentName, extraVars, agentId,
                sandbox.sandboxOn ? List.of(new SandboxLifecycle(fileService, artifactSetup.createChatSessionSink(params.sessionId), publicUrlConfiguration)) : null,
                null, null));
        var session = new InProcessAgentSession(params.sessionId, agent, true, new InMemoryToolPermissionStore());
        sandbox.sessionRef[0] = session;
        session.setOnIdle(() -> renewSessionOwnership(params.sessionId));
        // Turn-state listener goes first so the Redis turn key is written before the
        // RUNNING event is published to other pods. The liveness probe lets the registry's
        // heartbeat drop the key once the turn stops executing, even if no terminal event arrived.
        if (turnStateRegistry != null) {
            session.onEvent(turnStateRegistry.listener(params.sessionId, session::isTurnRunning));
        }
        session.onEvent(chatMessageService.listener(params.sessionId));
        if (eventPublisher != null) {
            session.onEvent(new SseEventBridge(params.sessionId, eventPublisher));
        }
        restoreAgentHistory(agent, params.sessionId);
        skillManager.restoreDefinitionSkills(session, params.state != null && params.state.agentConfig != null
                ? params.state.agentConfig.skillIds : null);
        restoreDynamicallyLoaded(params.state, params.sessionId, session, params.userId);
        logger.info("doRebuild done, sessionId={}, elapsedMs={}", params.sessionId, System.currentTimeMillis() - start);
        return session;
    }
    private void logRebuildStart(RebuildParams params, List<ToolCall> tools) {
        var toolCount = tools != null ? tools.size() : 0;
        var skillCount = params.state != null && params.state.skillIds != null ? params.state.skillIds.size() : 0;
        var subAgentCount = params.state != null && params.state.subAgentIds != null ? params.state.subAgentIds.size() : 0;
        var dynamicToolCount = params.state != null && params.state.tools != null ? params.state.tools.size() : 0;
        logger.info("doRebuild start, sessionId={}, fromAgent={}, baseTools={}, dynamicTools={}, skills={}, subAgents={}",
                params.sessionId, params.state != null && params.state.fromAgent, toolCount, dynamicToolCount, skillCount, subAgentCount);
    }
    private void renewSessionOwnership(String sessionId) {
        if (ownershipRegistry != null) {
            ownershipRegistry.claimOrRenew(sessionId);
        }
    }
    void restoreDynamicallyLoaded(SessionState state, String sessionId, InProcessAgentSession session,
                                  String callerUserId) {
        if (state == null) return;
        if (state.tools != null && !state.tools.isEmpty()) {
            try {
                logger.info("restore tools: {} ref(s) to resolve for session {}, refs={}", state.tools.size(), sessionId, state.tools);
                var resolved = toolRegistryService.resolveToolRefs(state.tools, sessionId, callerUserId);
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
                skillManager.applyCallerSkillsToSession(session, state.skillIds, callerUserId);
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
                    subAgentManager.applySubAgentsToSession(session, definitions, callerUserId);
                    logger.info("restored {} dynamically loaded sub-agents for session {}", definitions.size(), sessionId);
                }
            } catch (Exception e) {
                logger.warn("failed to restore dynamically loaded sub-agents, sessionId={}", sessionId, e);
            }
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
    record RebuildParams(String sessionId, SessionConfig config, List<ToolRef> toolRefs, String userId,
                         String agentName, SessionState state, String datasetAgentId,
                         List<AgentDatasetConfig> datasetConfig, Map<String, String> configVars,
                         SandboxConfig sandboxConfig, boolean allowSandboxReattach) {
    }
    private record SandboxSetup(ExecutionContext context, InProcessAgentSession[] sessionRef, boolean sandboxOn) {
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
                        PublicUrlConfiguration publicUrlConfiguration,
                        EventPublisher eventPublisher,
                        SessionOwnershipRegistry ownershipRegistry,
                        SystemSettingsService systemSettingsService,
                        MongoCollection<User> userCollection,
                        MediaProvider mediaProvider,
                        ApiUserQuotaService quotaService,
                        TurnStateRegistry turnStateRegistry,
                        ai.core.tool.ToolCallAsyncTaskManager asyncTaskManager) {
    }
}
