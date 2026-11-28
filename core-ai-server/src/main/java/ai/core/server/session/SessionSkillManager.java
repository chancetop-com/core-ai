package ai.core.server.session;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.ServerSkillTool;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillSdkCapabilityResolver;
import ai.core.server.skill.SkillService;
import ai.core.server.skill.telemetry.SkillTelemetryBuffer;
import ai.core.server.util.IdLists;
import ai.core.session.InProcessAgentSession;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.skill.telemetry.SkillTelemetryLifecycle;
import ai.core.skill.telemetry.SkillTelemetrySink;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.ReadSkillResourceTool;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class SessionSkillManager {
    private final Logger logger = LoggerFactory.getLogger(SessionSkillManager.class);
    private final ConcurrentMap<String, SessionSkillState> sessionSkillStates = new ConcurrentHashMap<>();

    private final SkillService skillService;
    private final MongoSkillProvider mongoSkillProvider;
    private final SkillArchiveBuilder skillArchiveBuilder;
    private final ChatMessageService chatMessageService;
    private SkillSdkCapabilityResolver capabilityResolver;
    private SkillTelemetryBuffer telemetryBuffer;

    public SessionSkillManager(SkillService skillService, MongoSkillProvider mongoSkillProvider,
                                SkillArchiveBuilder skillArchiveBuilder, ChatMessageService chatMessageService) {
        this.skillService = skillService;
        this.mongoSkillProvider = mongoSkillProvider;
        this.skillArchiveBuilder = skillArchiveBuilder;
        this.chatMessageService = chatMessageService;
    }

    public void setCapabilityResolver(SkillSdkCapabilityResolver capabilityResolver) {
        this.capabilityResolver = capabilityResolver;
    }

    public void setTelemetryBuffer(SkillTelemetryBuffer telemetryBuffer) {
        this.telemetryBuffer = telemetryBuffer;
    }

    public List<String> unloadSkills(String sessionId, List<String> skillIds, String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (!cleanSkillIds.isEmpty()) {
            skillService.resolveAccessibleSkills(cleanSkillIds, callerUserId);
        }
        var state = sessionSkillStates.get(sessionId);
        if (state == null || cleanSkillIds.isEmpty()) {
            return state == null ? List.of() : List.copyOf(state.allowedIds);
        }
        state.allowedIds.removeAll(cleanSkillIds);
        cleanSkillIds.forEach(state.skills::remove);
        state.registry.invalidateCache();
        chatMessageService.removeLoadedSkillIds(sessionId, cleanSkillIds);
        recomputeCapabilities(state, null);
        return List.copyOf(state.allowedIds);
    }

    public List<String> loadSkills(InProcessAgentSession session, List<String> skillIds, String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var qualifiedNames = applyCallerSkillsToSession(session, cleanSkillIds, callerUserId);
        chatMessageService.addLoadedSkillIds(session.id(), cleanSkillIds);
        return qualifiedNames;
    }

    ResolvedDefinitionSkills resolveAccessibleDefinitionSkills(AgentDefinition definition, String callerUserId) {
        var cleanSkillIds = definitionSkillIds(definition);
        if (cleanSkillIds.isEmpty()) return new ResolvedDefinitionSkills(List.of(), List.of());
        var skills = skillService.resolveAccessibleSkills(cleanSkillIds, callerUserId);
        return new ResolvedDefinitionSkills(cleanSkillIds, skills);
    }

    ResolvedDefinitionSkills resolveDefinitionSkills(AgentDefinition definition, String callerUserId,
                                                      boolean ownedEditable) {
        return ownedEditable ? resolveAccessibleDefinitionSkills(definition, callerUserId) : null;
    }

    List<String> loadResolvedDefinitionSkills(InProcessAgentSession session, ResolvedDefinitionSkills resolved) {
        if (resolved.skillIds.isEmpty()) return List.of();
        var qualifiedNames = applyResolvedSkillsToSession(session, resolved.skillIds, resolved.skills, null);
        chatMessageService.addLoadedSkillIds(session.id(), resolved.skillIds);
        return qualifiedNames;
    }

    public List<String> loadFrozenSkillsFromDefinition(InProcessAgentSession session, AgentDefinition definition) {
        var cleanSkillIds = definitionSkillIds(definition);
        if (cleanSkillIds.isEmpty()) return List.of();
        try {
            var qualifiedNames = applySkillsToSession(session, cleanSkillIds);
            chatMessageService.addLoadedSkillIds(session.id(), cleanSkillIds);
            return qualifiedNames;
        } catch (Exception e) {
            logger.warn("failed to load skills from definition, sessionId={}, skillIds={}", session.id(), cleanSkillIds, e);
            return List.of();
        }
    }

    List<String> loadDefinitionSkills(InProcessAgentSession session, AgentDefinition definition,
                                      ResolvedDefinitionSkills resolved) {
        return resolved != null
                ? loadResolvedDefinitionSkills(session, resolved)
                : loadFrozenSkillsFromDefinition(session, definition);
    }

    private List<String> definitionSkillIds(AgentDefinition definition) {
        var skillIds = definition.publishedConfig != null
                ? definition.publishedConfig.skillIds : definition.skillIds;
        return IdLists.clean(skillIds);
    }

    List<String> applySkillsToSession(InProcessAgentSession session, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var skills = skillService.resolveSkills(cleanSkillIds);
        if (skills.isEmpty()) {
            throw new NotFoundException("no skills found for ids: " + cleanSkillIds);
        }
        return applyResolvedSkillsToSession(session, cleanSkillIds, skills, null);
    }

    List<String> applyCallerSkillsToSession(InProcessAgentSession session, List<String> skillIds,
                                            String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var skills = skillService.resolveAccessibleSkills(cleanSkillIds, callerUserId);
        return applyResolvedSkillsToSession(session, cleanSkillIds, skills, callerUserId);
    }

    void restoreDefinitionSkills(InProcessAgentSession session, List<String> skillIds) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return;
        try {
            applySkillsToSession(session, cleanSkillIds);
            logger.info("restored {} definition skill(s) for session {}", cleanSkillIds.size(), session.id());
        } catch (Exception e) {
            logger.warn("failed to restore definition skills, sessionId={}", session.id(), e);
        }
    }

    private List<String> applyResolvedSkillsToSession(InProcessAgentSession session, List<String> cleanSkillIds,
                                                       List<SkillMetadata> skills, String callerUserId) {
        var state = sessionSkillStates.computeIfAbsent(session.id(), k -> initSkillState(session));
        state.allowedIds.addAll(cleanSkillIds);
        for (int i = 0; i < cleanSkillIds.size() && i < skills.size(); i++) {
            state.skills.put(cleanSkillIds.get(i), skills.get(i));
        }
        state.registry.invalidateCache();
        recomputeCapabilities(state, callerUserId);
        return skills.stream().map(SkillMetadata::getQualifiedName).toList();
    }

    private void recomputeCapabilities(SessionSkillState state, String callerUserId) {
        if (state == null || capabilityResolver == null) return;
        var agent = state.session.agent();
        if (agent == null) return;
        var userId = callerUserId != null ? callerUserId : agent.getExecutionContext().getUserId();
        try {
            agent.getExecutionContext().setSkillCapabilities(
                    capabilityResolver.resolve(state.session.id(), userId, state.skills));
        } catch (RuntimeException e) {
            logger.warn("failed to resolve skill capabilities, sessionId={}", state.session.id(), e);
            agent.getExecutionContext().setSkillCapabilities(List.of());
        }
    }

    public void removeSkillState(String sessionId) {
        sessionSkillStates.remove(sessionId);
    }

    private SessionSkillState initSkillState(InProcessAgentSession session) {
        var fresh = new SessionSkillState(session);
        fresh.registry.addProvider(mongoSkillProvider.scoped(fresh.allowedIds));
        var sink = telemetryBuffer != null ? telemetryBuffer.asSink() : SkillTelemetrySink.NOOP;
        ToolCall skillTool = ServerSkillTool.builder()
                .registry(fresh.registry)
                .skillService(skillService)
                .archiveBuilder(skillArchiveBuilder)
                .telemetryBuffer(telemetryBuffer)
                .build();
        ToolCall readResourceTool = ReadSkillResourceTool.builder()
                .registry(fresh.registry)
                .telemetrySink(sink)
                .build();
        session.loadTools(List.of(skillTool, readResourceTool));
        if (telemetryBuffer != null && session.agent() != null) {
            session.agent().addLifecycle(new SkillTelemetryLifecycle(fresh.registry, null, sink, null));
        }
        return fresh;
    }

    static final class SessionSkillState {
        final InProcessAgentSession session;
        final Set<String> allowedIds = ConcurrentHashMap.newKeySet();
        final ConcurrentMap<String, SkillMetadata> skills = new ConcurrentHashMap<>();
        final SkillRegistry registry = new SkillRegistry();

        SessionSkillState(InProcessAgentSession session) {
            this.session = session;
        }
    }

    record ResolvedDefinitionSkills(List<String> skillIds, List<SkillMetadata> skills) {
    }
}
