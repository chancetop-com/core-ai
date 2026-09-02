package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.SessionConfig;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.file.FileService;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.User;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.sandbox.SandboxLifecycle;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.CallerContexts;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.util.IdLists;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.web.sse.SseEventBridge;
import ai.core.prompt.PromptInject;
import ai.core.session.InMemoryToolPermissionStore;
import ai.core.session.InProcessAgentSession;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
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
    MongoCollection<User> userCollection;
    @Inject
    SkillService skillService;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    SessionRegistry sessionRegistry;
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
    PublicUrlConfiguration publicUrlConfiguration;
    @Inject
    SandboxSnapshotService sandboxSnapshotService;

    @Inject
    EventPublisher eventPublisher;
    @Inject
    SessionOwnershipRegistry ownershipRegistry;
    @Inject
    TurnStateRegistry turnStateRegistry;
    @Inject
    ChannelRegistry channelRegistry;
    @Inject
    SubAgentAssembler subAgentAssembler;
    @Inject
    AgentMemoryExperimentService memoryExperimentService;
    @Inject
    SystemSettingsService systemSettingsService;
    @Inject
    SessionAgentHelper sessionAgentHelper;
    @Inject
    ApiUserQuotaService apiUserQuotaService;
    @Inject
    ai.core.schedule.ScheduledTaskStore scheduledTaskStore;
    @Inject
    ai.core.server.asynctask.AsyncToolTaskService asyncToolTaskService;
    @Inject SessionActivityRegistry sessionActivityRegistry;

    private SessionSkillManager skillManager;
    private SessionSubAgentManager subAgentManager;
    private SessionRebuildManager rebuildManager;
    private SessionDatasetHelper datasetHelper;

    private SessionSkillManager skillManager() {
        if (skillManager == null) skillManager = new SessionSkillManager(skillService, mongoSkillProvider, skillArchiveBuilder, chatMessageService);
        return skillManager;
    }
    private SessionSubAgentManager subAgentManager() {
        if (subAgentManager == null) subAgentManager = new SessionSubAgentManager(chatMessageService, subAgentAssembler);
        return subAgentManager;
    }
    private SessionRebuildManager rebuildManager() {
        if (rebuildManager == null) {
            rebuildManager = new SessionRebuildManager(new SessionRebuildManager.Deps(chatMessageService, agentDefinitionCollection, skillManager(), subAgentManager(), sandboxService,
                    artifactSetup, toolRegistryService, systemPromptService, datasetService, datasetRecordService, fileService, publicUrlConfiguration, eventPublisher,
                    ownershipRegistry, systemSettingsService, userCollection, sessionAgentHelper.mediaProvider, apiUserQuotaService, turnStateRegistry, asyncTaskManager()));
        }
        return rebuildManager;
    }
    private ai.core.tool.ToolCallAsyncTaskManager asyncTaskManager() {
        return asyncToolTaskService == null ? null : asyncToolTaskService.manager();
    }

    private SessionDatasetHelper datasetHelper() {
        if (datasetHelper == null) datasetHelper = new SessionDatasetHelper(datasetService, datasetRecordService);
        return datasetHelper;
    }
    private PromptInject channelInject(SessionConfig config) {
        if (config == null || config.channelType == null || config.channelType.isBlank()) return null;
        return () -> "You are communicating with the user through the " + config.channelType + " channel.";
    }
    private void attachSessionListeners(InProcessAgentSession session, String sessionId) {
        // Turn-state listener goes first so the Redis turn key is written before the
        // RUNNING event is published to other pods. The liveness probe lets the registry's
        // heartbeat drop the key once the turn stops executing, even if no terminal event arrived.
        if (turnStateRegistry != null) {
            session.onEvent(turnStateRegistry.listener(sessionId, session::isTurnRunning));
        }
        session.onEvent(chatMessageService.listener(sessionId));
        session.onEvent(new SseEventBridge(sessionId, eventPublisher));
    }
    public void touchActivity(String sessionId) {
        sessionLastActivity.put(sessionId, System.currentTimeMillis());
        sandboxService.renewSandbox(sessionId);
    }
    public String createSession(SessionConfig config, String userId, String source) {
        return createSession(config, userId, source, null);
    }
    public String createSession(SessionConfig config, String userId, String source, String apiKeyId) {
        var effectiveConfig = config != null ? config : new SessionConfig();
        var sessionId = UUID.randomUUID().toString();
        var context = new SessionContextBuilder(artifactSetup, fileService, publicUrlConfiguration, systemSettingsService, sessionAgentHelper.mediaProvider, apiUserQuotaService).withAsyncTaskManager(asyncTaskManager()).build(sessionId, userId);
        CallerContexts.attach(context, userCollection, userId);
        var sandboxOn = sandboxService.isSandboxEnabled(null);
        var toolRegistry = datasetHelper().buildSessionToolRegistry(effectiveConfig, sessionId, scheduledTaskStore);
        var sessionDatasetConfig = datasetHelper().sessionDatasetConfig(effectiveConfig);
        var extraVars = datasetHelper().buildExtraVars(effectiveConfig, sessionDatasetConfig);
        var agent = subAgentManager().buildAgent(new SessionSubAgentManager.BuildAgentParams(
                effectiveConfig, toolRegistry, context, null, extraVars, null,
                sandboxOn ? List.of(new SandboxLifecycle(fileService, artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration)) : null,
                null, channelInject(effectiveConfig)));
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        session.setOnIdle(() -> onSessionHeartbeat(sessionId, session));
        attachSessionListeners(session, sessionId);
        var sandbox = sandboxService.createSessionSandbox(null, sessionId, userId, session::dispatchEvent);
        if (sandbox != null) context.sandbox(sandbox);
        initializeSession(session, new SessionRegistry.SessionRegistration(
                sessionId, userId, null, source, null, apiKeyId, sessionDatasetConfig));
        return sessionId;
    }
    public SessionCreationResult createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId) {
        return createSessionFromAgent(definition, overrides, userId, "chat", null);
    }
    public SessionCreationResult createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId, String source) {
        return createSessionFromAgent(definition, overrides, userId, source, null);
    }
    public SessionCreationResult createSessionFromAgent(AgentDefinition definition, SessionConfig overrides, String userId, String source, String apiKeyId) {
        var ownedEditable = AgentDependencyAccessPolicy.isOwnedEditable(definition, userId);
        var executableDefinition = AgentDependencyAccessPolicy.executableSessionAgent(definition, userId);
        var resolvedDefinitionSkills = skillManager().resolveDefinitionSkills(executableDefinition, userId, ownedEditable);
        var config = subAgentManager().toSessionConfig(executableDefinition);
        if (overrides != null) {
            if (overrides.model != null) config.model = overrides.model;
            if (overrides.multiModalModel != null) config.multiModalModel = overrides.multiModalModel;
            if (overrides.temperature != null) config.temperature = overrides.temperature;
            if (overrides.reasoningEffort != null) config.reasoningEffort = overrides.reasoningEffort;
            if (overrides.systemPrompt != null) config.systemPrompt = overrides.systemPrompt;
            if (overrides.maxTurns != null) config.maxTurns = overrides.maxTurns;
            if (overrides.channelType != null) config.channelType = overrides.channelType;
        }
        var sessionId = UUID.randomUUID().toString();
        var datasetConfig = sessionAgentHelper.resolveDatasetConfig(executableDefinition, config, overrides);
        var buildResult = buildAgentForDefinition(executableDefinition, sessionId, userId, config, datasetConfig);
        var agent = buildResult.agent;
        var session = new InProcessAgentSession(sessionId, agent, true, new InMemoryToolPermissionStore());
        buildResult.sessionRef[0] = session;
        session.setOnIdle(() -> onSessionHeartbeat(sessionId, session));
        attachSessionListeners(session, sessionId);
        initializeSession(session, new SessionRegistry.SessionRegistration(
                sessionId, userId, executableDefinition.id, source, null, apiKeyId, datasetConfig));
        var executableConfig = executableDefinition.publishedConfig;
        var loadedSkillIds = executableConfig != null ? executableConfig.skillIds : executableDefinition.skillIds;
        var loadedSubAgentIds = executableConfig != null ? executableConfig.subAgentIds : executableDefinition.subAgentIds;
        try {
            skillManager().loadDefinitionSkills(session, executableDefinition, resolvedDefinitionSkills);
            subAgentManager().loadSubAgentsFromDefinition(session, executableDefinition, userId);
        } catch (RuntimeException | Error e) {
            abortSessionCreation(sessionId);
            throw e;
        }
        return new SessionCreationResult(sessionId,
                IdLists.clean(loadedSubAgentIds),
                IdLists.clean(loadedSkillIds),
                executableDefinition);
    }

    private AgentBuildResult buildAgentForDefinition(AgentDefinition definition, String sessionId, String userId,
                                                      SessionConfig config, List<AgentDatasetConfig> datasetConfig) {
        var sandboxConfig = sandboxService.getEffectiveConfig(definition);
        var sandboxOn = sandboxService.isSandboxEnabled(sandboxConfig);
        var sessionRef = new InProcessAgentSession[1];
        var sandbox2 = sandboxService.createSessionSandbox(sandboxConfig, sessionId, userId, event -> {
            if (sessionRef[0] != null) sessionRef[0].dispatchEvent(event);
        });
        var toolRegistry = subAgentManager().resolveTopLevelToolsToRegistry(definition, sessionId, userId);
        datasetHelper().addDatasetToolsToRegistry(toolRegistry, datasetConfig, definition.id, sessionId);
        var extraVars = datasetHelper().buildExtraVars(config, datasetConfig);
        var context = new SessionContextBuilder(artifactSetup, fileService, publicUrlConfiguration, systemSettingsService, sessionAgentHelper.mediaProvider, apiUserQuotaService).withAsyncTaskManager(asyncTaskManager()).build(sessionId, userId);
        CallerContexts.attach(context, userCollection, userId);
        if (sandbox2 != null) context.sandbox(sandbox2);

        var injectionResult = memoryExperimentService.prepareInjection(definition.id);
        var memoryInject = injectionResult.injected ? injectionResult.promptInject : null;

        var agent = subAgentManager().buildAgent(new SessionSubAgentManager.BuildAgentParams(
                config, toolRegistry, context, definition.name, extraVars, definition.id,
                sandboxOn ? List.of(new SandboxLifecycle(fileService, artifactSetup.createChatSessionSink(sessionId), publicUrlConfiguration)) : null,
                memoryInject, channelInject(config)));

        var experimentConfig = memoryExperimentService.getConfig(definition.id);
        if (experimentConfig != null) memoryExperimentService.startRun(definition.id, sessionId, "session:" + sessionId, experimentConfig, injectionResult);
        return new AgentBuildResult(agent, sessionRef);
    }

    private void initializeSession(InProcessAgentSession session, SessionRegistry.SessionRegistration registration) {
        var sessionId = registration.sessionId();
        sessions.put(sessionId, session);
        boolean claimed;
        try {
            touchActivity(sessionId);
            claimed = claimOwnership(sessionId);
        } catch (RuntimeException | Error e) {
            cleanupRuntime(sessionId);
            throw e;
        }
        if (!claimed) {
            cleanupRuntime(sessionId);
            throw new IllegalStateException("failed to claim session ownership, sessionId=" + sessionId);
        }
        try {
            sessionRegistry.create(registration);
        } catch (RuntimeException | Error e) {
            cleanupRuntime(sessionId);
            throw e;
        }
    }
    private boolean claimOwnership(String sessionId) {
        return sessionAgentHelper.claimOwnership(sessionId);
    }
    private void renewSessionOwnership(String sessionId) {
        sessionAgentHelper.renewSessionOwnership(sessionId);
    }
    // The session driver calls this both while idle and every few seconds during a turn. A long turn
    // produces no commands, so without the activity touch below its sessionLastActivity would go stale
    // and cleanupIdleSessions would close the session out from under a run that is still working.
    void onSessionHeartbeat(String sessionId, InProcessAgentSession session) {
        renewSessionOwnership(sessionId);
        if (session.isTurnRunning()) touchActivity(sessionId);
    }
    public InProcessAgentSession getSession(String sessionId) {
        return getSession(sessionId, null);
    }
    public InProcessAgentSession getSession(String sessionId, SessionState state) {
        return getSession(sessionId, state, null);
    }
    public InProcessAgentSession getSession(String sessionId, SessionState state, String callerUserId) {
        var session = sessions.get(sessionId);
        if (session != null) {
            if (callerUserId != null && !callerUserId.isBlank()) requireSessionCaller(sessionId, callerUserId);
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
            var rebuilt = rebuildManager().rebuildSession(id, effectiveState, callerUserId);
            if (rebuilt != null) {
                // Take over the driver heartbeat so a long turn on a rebuilt session also keeps
                // its activity fresh, not just its ownership lease.
                rebuilt.setOnIdle(() -> onSessionHeartbeat(id, rebuilt));
                if (!sessionAgentHelper.claimOrConfirmOwnership(id)) {
                    logger.warn("failed to claim rebuilt session ownership, sessionId={}", id);
                    rebuilt.close();
                    return null;
                }
                logger.info("session rebuilt successfully, sessionId={}", id);
            }
            return rebuilt;
        });
        if (built == null) {
            throw new NotFoundException("session not found, sessionId=" + sessionId);
        }
        if (callerUserId != null && !callerUserId.isBlank()) requireSessionCaller(sessionId, callerUserId);
        touchActivity(sessionId);
        return built;
    }
    public InProcessAgentSession getSessionForAgentCaller(String sessionId, String agentId, String callerUserId) {
        requireSessionOwner(sessionId, callerUserId);
        String sessionAgentId = sessionRegistry.requireAgentId(sessionId);
        if (agentId == null || agentId.isBlank() || !agentId.equals(sessionAgentId)) {
            throw new ForbiddenException("session is unavailable");
        }
        return getSession(sessionId);
    }
    public void requireSessionOwner(String sessionId, String callerUserId) {
        String ownerUserId = sessionRegistry.requireUserId(sessionId);
        if (callerUserId == null || callerUserId.isBlank() || !callerUserId.equals(ownerUserId)) {
            throw new ForbiddenException("session is unavailable");
        }
    }
    public void touchSession(String sessionId) {
        if (ownershipRegistry != null) ownershipRegistry.claimOrRenew(sessionId);
    }
    public void closeSession(String sessionId) {
        cleanupRuntime(sessionId);
    }

    public void abortSessionCreation(String sessionId) {
        cleanupRuntime(sessionId);
        sessionRegistry.softDelete(null, sessionId);
    }

    private void cleanupRuntime(String sessionId) {
        var session = sessions.remove(sessionId);
        // close() aborts an in-flight turn and emits its terminal event first, so the persistence
        // listener writes the partial reply and the turn state clears before the teardown below
        // discards the buffers those listeners write into.
        if (session != null) session.close();
        // Belt and braces: the turn key must never outlive the runtime that owns it, even if the
        // session was already gone or its listener chain never fired.
        if (turnStateRegistry != null) turnStateRegistry.clear(sessionId);
        chatMessageService.flushPendingTurn(sessionId);
        skillManager().removeSkillState(sessionId);
        sessionLastActivity.remove(sessionId);
        SessionSandboxSnapshotHelper.captureBeforeRelease(sandboxService, sandboxSnapshotService, sessionId);
        sandboxService.releaseSandbox(sessionId);
        chatMessageService.onSessionClosed(sessionId);
        sessionChannelService.close(sessionId);
        if (channelRegistry != null) channelRegistry.removeSessionBridge(sessionId);
        sessionAgentHelper.releaseOwnership(sessionId);
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
            var durableActivity = sessionActivityRegistry != null ? sessionActivityRegistry.lastActivity(sessionId) : 0L;
            if (durableActivity >= threshold) { // cross-pod terminal activity keeps this session alive; treat it as local.
                sessionLastActivity.put(sessionId, durableActivity);
                sandboxService.renewSandbox(sessionId);
            } else try {
                    logger.info("closing idle session, sessionId={}, idleMs={}", sessionId, System.currentTimeMillis() - entry.getValue());
                    closeSession(sessionId);
                    closed++;
                } catch (Exception e) {
                    logger.warn("failed to close idle session, sessionId={}", sessionId, e);
                }
        }
        return closed;
    }
    public List<ToolRef> loadToolRefs(String sessionId, List<ToolRef> toolRefs, String callerUserId) {
        requireSessionCaller(sessionId, callerUserId);
        var session = getSession(sessionId);
        var tools = toolRegistryService.resolveToolRefs(toolRefs, sessionId, callerUserId);
        if (tools.isEmpty()) {
            throw new NotFoundException("no tools found for refs: " + toolRefs);
        }
        session.loadTools(tools);
        chatMessageService.addLoadedTools(sessionId, toolRefs);
        return toolRefs;
    }
    public List<String> unloadSkills(String sessionId, List<String> skillIds, String callerUserId) {
        requireSessionCaller(sessionId, callerUserId);
        return skillManager().unloadSkills(sessionId, skillIds, callerUserId);
    }
    public List<String> loadSkills(String sessionId, List<String> skillIds, String callerUserId) {
        requireSessionCaller(sessionId, callerUserId);
        var session = getSession(sessionId);
        return skillManager().loadSkills(session, skillIds, callerUserId);
    }
    public List<String> loadSubAgents(String sessionId, List<AgentDefinition> definitions, String callerUserId) {
        requireSessionCaller(sessionId, callerUserId);
        var session = getSession(sessionId);
        return subAgentManager().loadSubAgents(session, definitions, callerUserId);
    }

    void requireSessionCaller(String sessionId, String callerUserId) {
        String ownerUserId = sessionRegistry.requireUserId(sessionId);
        if (callerUserId == null || callerUserId.isBlank() || !callerUserId.equals(ownerUserId)) {
            throw new ForbiddenException("session is unavailable");
        }
    }
    public record SessionCreationResult(String sessionId, List<String> loadedSubAgentIds, List<String> loadedSkillIds, AgentDefinition executableDefinition) { }
    private record AgentBuildResult(Agent agent, InProcessAgentSession[] sessionRef) { }
}