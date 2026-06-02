package ai.core.server.web;

import ai.core.api.server.session.CreateSessionRequest;
import ai.core.api.server.session.IdName;
import ai.core.server.agent.AgentDefinitionService;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.server.session.SessionState;
import ai.core.server.skill.SkillService;
import ai.core.server.tool.ToolRegistryService;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class SessionCreateHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionCreateHelper.class);
    private static final String SESSION_STATE_KEY = "agent-session-state";

    @Inject
    WebContext webContext;
    @Inject
    AgentSessionManager sessionManager;
    @Inject
    AgentDefinitionService agentDefinitionService;
    @Inject
    ToolRegistryService toolRegistryService;
    @Inject
    SkillService skillService;
    @Inject
    ChatMessageService chatMessageService;

    String createSessionFromAgent(String agentId, SessionState state, String userId,
                                  List<IdName> loadedSubAgents, List<IdName> loadedSkills) {
        var agent = agentDefinitionService.getEntity(agentId);
        var result = sessionManager.createSessionFromAgent(agent, state.config, userId);
        state.fromAgent = true;
        state.agentConfig = buildAgentConfigSnapshot(agent);
        if (result.loadedSubAgentIds() != null && !result.loadedSubAgentIds().isEmpty()) {
            for (var id : result.loadedSubAgentIds()) {
                String name = resolveAgentName(id);
                var v = new IdName();
                v.id = id;
                v.name = name;
                loadedSubAgents.add(v);
            }
        }
        if (result.loadedSkillIds() != null && !result.loadedSkillIds().isEmpty()) {
            for (var id : result.loadedSkillIds()) {
                String name = resolveSkillName(id);
                var v = new IdName();
                v.id = id;
                v.name = name != null ? name : id;
                loadedSkills.add(v);
            }
        }
        return result.sessionId();
    }

    SessionState.AgentConfigSnapshot buildAgentConfigSnapshot(ai.core.server.domain.AgentDefinition agent) {
        var published = agent.publishedConfig;
        var toolRefs = published != null ? published.tools : agent.tools;
        var snapshot = new SessionState.AgentConfigSnapshot();
        snapshot.agentId = agent.id;
        snapshot.agentName = agent.name;
        snapshot.systemPrompt = published != null && published.systemPrompt != null ? published.systemPrompt : agent.systemPrompt;
        snapshot.systemPromptId = published != null && published.systemPromptId != null ? published.systemPromptId : agent.systemPromptId;
        snapshot.model = published != null && published.model != null ? published.model : agent.model;
        snapshot.temperature = published != null && published.temperature != null ? published.temperature : agent.temperature;
        snapshot.maxTurns = published != null && published.maxTurns != null ? published.maxTurns : agent.maxTurns;
        snapshot.inputTemplate = published != null && published.inputTemplate != null ? published.inputTemplate : agent.inputTemplate;
        snapshot.variables = published != null && published.variables != null ? published.variables : agent.variables;
        snapshot.tools = toolRefs;
        snapshot.outputDatasetId = published != null && published.outputDatasetId != null ? published.outputDatasetId : agent.outputDatasetId;
        return snapshot;
    }

    List<IdName> loadToolsOnSessionCreate(String sessionId, CreateSessionRequest request, SessionState sessionState) {
        if (request.tools == null || request.tools.isEmpty()) return null;

        var toolRefs = request.tools.stream()
                .filter(v -> v != null && v.id != null)
                .map(v -> {
                    var ref = new ToolRef();
                    ref.id = v.id;
                    ref.type = v.type != null ? ToolSourceType.valueOf(v.type) : null;
                    ref.source = v.source;
                    if (ref.type == null) ref.inferTypeFromId();
                    return ref;
                }).toList();

        if (toolRefs.isEmpty()) return null;

        var loadedTools = toolRegistryService.resolveToolRefs(toolRefs);
        if (loadedTools.isEmpty()) {
            LOGGER.warn("no tools found for refs, skipping: {}", toolRefs);
            return null;
        }

        var session = sessionManager.getSession(sessionId, sessionState);
        session.loadTools(loadedTools);
        chatMessageService.addLoadedTools(sessionId, toolRefs);
        return loadedTools.stream().map(t -> {
            var v = new IdName();
            v.id = t.getName();
            v.name = t.getName();
            return v;
        }).toList();
    }

    List<IdName> loadSkillsOnSessionCreate(String sessionId, CreateSessionRequest request) {
        if (request.skillIds == null || request.skillIds.isEmpty()) return null;
        var names = sessionManager.loadSkills(sessionId, request.skillIds);
        var result = new ArrayList<IdName>(request.skillIds.size());
        for (int i = 0; i < request.skillIds.size() && i < names.size(); i++) {
            var v = new IdName();
            v.id = request.skillIds.get(i);
            v.name = names.get(i);
            result.add(v);
        }
        return result;
    }

    void loadExtraSubAgentsOnSessionCreate(String sessionId, CreateSessionRequest request, List<IdName> loadedSubAgents) {
        if (request.subAgentIds == null || request.subAgentIds.isEmpty()) return;
        var definitions = request.subAgentIds.stream()
                .map(id -> {
                    try {
                        return agentDefinitionService.getEntity(id);
                    } catch (Exception e) {
                        LOGGER.warn("extra subagent not found, id={}", id);
                        return null;
                    }
                })
                .filter(def -> def != null)
                .toList();
        if (definitions.isEmpty()) return;
        var names = sessionManager.loadSubAgents(sessionId, definitions);
        for (int i = 0; i < definitions.size() && i < names.size(); i++) {
            var v = new IdName();
            v.id = definitions.get(i).id;
            v.name = names.get(i);
            loadedSubAgents.add(v);
        }
    }

    String resolveSkillName(String id) {
        try {
            return skillService.get(id).name;
        } catch (Exception e) {
            return id;
        }
    }

    String resolveAgentName(String id) {
        try {
            return agentDefinitionService.getEntity(id).name;
        } catch (Exception e) {
            return id;
        }
    }

    void saveSessionState(String sessionId, SessionState state) {
        var httpSession = webContext.request().session();
        httpSession.set(SESSION_STATE_KEY + ":" + sessionId, state.toJson());
    }
}
