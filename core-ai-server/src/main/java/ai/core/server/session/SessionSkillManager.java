package ai.core.server.session;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.ServerSkillTool;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.util.IdLists;
import ai.core.session.InProcessAgentSession;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
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

    public SessionSkillManager(SkillService skillService, MongoSkillProvider mongoSkillProvider,
                                SkillArchiveBuilder skillArchiveBuilder, ChatMessageService chatMessageService) {
        this.skillService = skillService;
        this.mongoSkillProvider = mongoSkillProvider;
        this.skillArchiveBuilder = skillArchiveBuilder;
        this.chatMessageService = chatMessageService;
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
        state.registry.invalidateCache();
        chatMessageService.removeLoadedSkillIds(sessionId, cleanSkillIds);
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
        var qualifiedNames = applyResolvedSkillsToSession(session, resolved.skillIds, resolved.skills);
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
        return applyResolvedSkillsToSession(session, cleanSkillIds, skills);
    }

    List<String> applyCallerSkillsToSession(InProcessAgentSession session, List<String> skillIds,
                                            String callerUserId) {
        var cleanSkillIds = IdLists.clean(skillIds);
        if (cleanSkillIds.isEmpty()) return List.of();
        var skills = skillService.resolveAccessibleSkills(cleanSkillIds, callerUserId);
        return applyResolvedSkillsToSession(session, cleanSkillIds, skills);
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
                                                       List<SkillMetadata> skills) {
        var state = sessionSkillStates.computeIfAbsent(session.id(), k -> initSkillState(session));
        state.allowedIds.addAll(cleanSkillIds);
        state.registry.invalidateCache();
        return skills.stream().map(SkillMetadata::getQualifiedName).toList();
    }

    public void removeSkillState(String sessionId) {
        sessionSkillStates.remove(sessionId);
    }

    private SessionSkillState initSkillState(InProcessAgentSession session) {
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
    }

    static final class SessionSkillState {
        final Set<String> allowedIds = ConcurrentHashMap.newKeySet();
        final SkillRegistry registry = new SkillRegistry();
    }

    record ResolvedDefinitionSkills(List<String> skillIds, List<SkillMetadata> skills) {
    }
}
