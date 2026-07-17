package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.media.MediaProvider;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.file.FileDownloadUrlResolver;
import ai.core.server.file.FileService;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.DatasetPermission;
import ai.core.server.domain.ToolRef;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.run.SubmitArtifactsTool;
import ai.core.server.sandbox.LazySandbox;
import ai.core.server.sandbox.SandboxLifecycle;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.tool.tools.InternalUrlResolver;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.web.sse.SseEventBridge;
import ai.core.prompt.PromptInject;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.session.InProcessAgentSession;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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
    @Inject
    FileService fileService;
    @Inject
    SandboxSnapshotService sandboxSnapshotService;

    EventPublisher eventPublisher;
    SessionOwnershipRegistry ownershipRegistry;
    @Inject
    ChannelRegistry channelRegistry;
    @Inject
    SubAgentAssembler subAgentAssembler;
    @Inject
    AgentMemoryExperimentService memoryExperimentService;
    @Inject
    MediaProvider mediaProvider;

    private SessionSkillManager skillManager;
    private SessionSubAgentManager subAgentManager;
    private SessionRebuildManager rebuildManager;
    private SessionDatasetHelper datasetHelper;

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
            subAgentManager = new SessionSubAgentManager(chatMessageService, subAgentAssembler);
        }
        return subAgentManager;
    }

    private SessionRebuildManager rebuildManager() {
        if (rebuildManager == null) {
            rebuildManager = new SessionRebuildManager(new SessionRebuildManager.Deps(chatMessageService, agentDefinitionCollection,
                    skillManager(), subAgentManager(), sandboxService, artifactSetup,
                    toolRegistryService, systemPromptService, datasetService, datasetRecordService, fileService, eventPublisher, ownershipRegistry));
        }
        return rebuildManager;
    }

    private SessionDatasetHelper datasetHelper() {
        if (datasetHelper == null) {
            datasetHelper = new SessionDatasetHelper(datasetService, datasetRecordService);
        }
        return datasetHelper;
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
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId)
                .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, SubmitArtifactsTool.publicUrl))
                .build();
        context.setMediaProvider(mediaProvider);
        var sandboxOn = sandboxService.isSandboxEnabled(null);
        var toolRegistry = datasetHelper().buildSessionToolRegistry(effectiveConfig, sessionId);
        Map<String, Object> extraVars = null;
        if (hasText(effectiveConfig.datasetId)) {
            var dp = new AgentDatasetConfig();
            dp.datasetId = effectiveConfig.datasetId;
            dp.permission = DatasetPermission.READ;
            var datasetConfig = List.of(dp);
            effectiveConfig.systemPrompt = datasetHelper().appendDatasetInstructions(effectiveConfig.systemPrompt, datasetConfig);
            extraVars = datasetHelper().buildDatasetSystemVars(datasetConfig);
        }
        if (effectiveConfig.channelType != null && !effectiveConfig.channelType.isBlank()) {
            if (extraVars == null) extraVars = new HashMap<>();
            extraVars.put("system.channel.type", effectiveConfig.channelType);
        }
        var agent = subAgentManager().buildAgent(new SessionSubAgentManager.BuildAgentParams(
                effectiveConfig, toolRegistry, context, null, extraVars, null,
                sandboxOn ? List.of(new SandboxLifecycle(fileService, artifactSetup.createChatSessionSink(sessionId))) : null,
                null, channelInject(effectiveConfig)));
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        session.setOnIdle(() -> renewSessionOwnership(sessionId));
        attachSessionListeners(session, sessionId);
        chatMessageService.registerSession(sessionId, ChatMessageService.SessionMeta.of(userId, null, source));
        var sandbox = sandboxService.createSessionSandbox(null, sessionId, userId, session::dispatchEvent);
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
        var sessionId = UUID.randomUUID().toString();
        var datasetConfig = resolveDatasetConfig(definition, config, overrides);
        var buildResult = buildAgentForDefinition(definition, sessionId, userId, config, datasetConfig);
        var agent = buildResult.agent;

        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        buildResult.sessionRef[0] = session;
        session.setOnIdle(() -> renewSessionOwnership(sessionId));
        attachSessionListeners(session, sessionId);
        chatMessageService.registerSession(sessionId, ChatMessageService.SessionMeta.of(userId, definition.id, source));
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
                IdLists.clean(loadedSubAgentIds),
                IdLists.clean(loadedSkillIds));
    }

    private List<AgentDatasetConfig> resolveDatasetConfig(AgentDefinition definition, SessionConfig config, SessionConfig overrides) {
        var datasetConfig = AgentDefinitionService.resolveDatasetConfig(definition);
        if (overrides != null && overrides.datasetConfigs != null && !overrides.datasetConfigs.isEmpty()) {
            datasetConfig = overrides.datasetConfigs.stream().map(entry -> {
                var perm = new AgentDatasetConfig();
                perm.datasetId = entry.datasetId;
                perm.permission = DatasetPermission.valueOf(entry.permission);
                perm.isOutput = entry.isOutput;
                return perm;
            }).toList();
            config.datasetConfigs = overrides.datasetConfigs;
        } else if (overrides != null && hasText(overrides.datasetId)) {
            var overridePerm = new AgentDatasetConfig();
            overridePerm.datasetId = overrides.datasetId;
            overridePerm.permission = DatasetPermission.READ;
            datasetConfig = List.of(overridePerm);
            config.datasetId = overrides.datasetId;
        }
        return datasetConfig;
    }

    private AgentBuildResult buildAgentForDefinition(AgentDefinition definition, String sessionId, String userId,
                                                      SessionConfig config, List<AgentDatasetConfig> datasetConfig) {
        var sandboxConfig = sandboxService.getEffectiveConfig(definition);
        var sandboxOn = sandboxService.isSandboxEnabled(sandboxConfig);
        var sessionRef = new InProcessAgentSession[1];
        var sandbox2 = sandboxService.createSessionSandbox(sandboxConfig, sessionId, userId,
                event -> {
                    if (sessionRef[0] != null) sessionRef[0].dispatchEvent(event);
                });

        var toolRegistry = subAgentManager().resolveToolsToRegistry(definition, sessionId);
        datasetHelper().addDatasetToolsToRegistry(toolRegistry, datasetConfig, definition.id, sessionId);
        Map<String, Object> extraVars = null;
        if (datasetConfig != null && !datasetConfig.isEmpty()) {
            config.systemPrompt = datasetHelper().appendDatasetInstructions(config.systemPrompt, datasetConfig);
            extraVars = datasetHelper().buildDatasetSystemVars(datasetConfig);
        }
        if (config.channelType != null && !config.channelType.isBlank()) {
            if (extraVars == null) extraVars = new HashMap<>();
            extraVars.put("system.channel.type", config.channelType);
        }
        var context = ExecutionContext.builder().sessionId(sessionId).userId(userId)
                .customVariable(InternalUrlResolver.CONTEXT_KEY, new FileDownloadUrlResolver(fileService, SubmitArtifactsTool.publicUrl))
                .build();
        if (sandbox2 != null) context.sandbox(sandbox2);

        var injectionResult = memoryExperimentService.prepareInjection(definition.id);
        var memoryInject = injectionResult.injected ? injectionResult.promptInject : null;

        var agent = subAgentManager().buildAgent(new SessionSubAgentManager.BuildAgentParams(
                config, toolRegistry, context, definition.name, extraVars, definition.id,
                sandboxOn ? List.of(new SandboxLifecycle(fileService, artifactSetup.createChatSessionSink(sessionId))) : null,
                memoryInject, channelInject(config)));

        var experimentConfig = memoryExperimentService.getConfig(definition.id);
        if (experimentConfig != null) {
            memoryExperimentService.startRun(definition.id, sessionId, "session:" + sessionId, experimentConfig, injectionResult);
        }
        return new AgentBuildResult(agent, sessionRef);
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
        captureSandboxSnapshot(sessionId);
        sandboxService.releaseSandbox(sessionId);
        chatMessageService.onSessionClosed(sessionId);
        sessionChannelService.close(sessionId);
        if (channelRegistry != null) {
            channelRegistry.removeSessionBridge(sessionId);
        }
        if (ownershipRegistry != null) {
            ownershipRegistry.release(sessionId);
        }
    }

    // Capture only on the session-close path (60min idle cleanup + explicit close).
    // Run/workflow/OCG releases and shutdown() intentionally never capture (v1 scope).
    private void captureSandboxSnapshot(String sessionId) {
        if (sandboxSnapshotService == null || !sandboxSnapshotService.enabled()) return;
        try {
            var sandbox = sandboxService.getSandbox(sessionId);
            if (!(sandbox instanceof LazySandbox lazy)) return;
            if (!lazy.snapshotDirty()) return;
            var ip = lazy.ip();
            var port = lazy.port();
            if (ip == null || port == 0) return; // sandbox never materialized
            if (!lazy.isDelegateTracked()) {
                logger.info("skip snapshot capture, sandbox already released by ttl cleanup: sessionId={}", sessionId);
                return;
            }
            sandboxSnapshotService.captureBeforeRelease(sessionId, lazy.userId(), lazy.snapshotEpoch(), ip, port, lazy.image());
        } catch (Exception e) {
            logger.warn("sandbox snapshot capture failed, releasing anyway: sessionId={}", sessionId, e);
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

    public List<ToolRef> loadToolRefs(String sessionId, List<ToolRef> toolRefs) {
        var session = getSession(sessionId);
        var tools = toolRegistryService.resolveToolRefs(toolRefs, sessionId);
        if (tools.isEmpty()) {
            throw new NotFoundException("no tools found for refs: " + toolRefs);
        }
        session.loadTools(tools);
        chatMessageService.addLoadedTools(sessionId, toolRefs);
        return toolRefs;
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private PromptInject channelInject(SessionConfig config) {
        if (config == null || config.channelType == null || config.channelType.isBlank()) return null;
        return () -> "You are communicating with the user through the " + config.channelType + " channel.";
    }

    public record SessionCreationResult(String sessionId, List<String> loadedSubAgentIds, List<String> loadedSkillIds) {
    }

    private record AgentBuildResult(Agent agent, InProcessAgentSession[] sessionRef) {
    }
}
