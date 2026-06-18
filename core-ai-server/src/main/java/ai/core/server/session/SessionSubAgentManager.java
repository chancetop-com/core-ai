package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.SessionConfig;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.domain.AgentDefinition;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.SubAgentToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Session-side orchestration around sub-agents: loading them as session tools and tracking loaded ids.
 * The actual sub-agent build (tools + skills) is delegated to {@link SubAgentAssembler}, shared with the run path.
 *
 * @author stephen
 */
public class SessionSubAgentManager {
    private final ChatMessageService chatMessageService;
    private final SubAgentAssembler subAgentAssembler;

    public SessionSubAgentManager(ChatMessageService chatMessageService, SubAgentAssembler subAgentAssembler) {
        this.chatMessageService = chatMessageService;
        this.subAgentAssembler = subAgentAssembler;
    }

    public List<String> loadSubAgents(InProcessAgentSession session, List<AgentDefinition> definitions) {
        var names = applySubAgentsToSession(session, definitions);
        var ids = definitions.stream().map(d -> d.id).toList();
        chatMessageService.addLoadedSubAgentIds(session.id(), ids);
        return names;
    }

    public List<String> loadSubAgentsFromDefinition(InProcessAgentSession session, AgentDefinition definition) {
        var subAgents = subAgentAssembler.assemble(definition.subAgentIds, session.id());
        var names = new ArrayList<String>();
        for (var subAgent : subAgents) {
            session.loadTools(List.of(subAgent));
            names.add(subAgent.getName());
        }
        return names;
    }

    List<String> applySubAgentsToSession(InProcessAgentSession session, List<AgentDefinition> definitions) {
        var names = new ArrayList<String>();
        for (var definition : definitions) {
            var subAgent = subAgentAssembler.buildSubAgent(definition, session.id());
            var subAgentToolCall = SubAgentToolCall.builder().subAgent(subAgent).build();
            session.loadTools(List.of(subAgentToolCall));
            names.add(definition.name);
        }
        return names;
    }

    public List<ToolCall> resolveTools(AgentDefinition definition) {
        return subAgentAssembler.resolveTools(definition, null);
    }

    public List<ToolCall> resolveTools(AgentDefinition definition, String sessionId) {
        return subAgentAssembler.resolveTools(definition, sessionId);
    }

    public SessionConfig toSessionConfig(AgentDefinition definition) {
        return subAgentAssembler.toSessionConfig(definition);
    }

    public Agent buildAgent(SessionConfig config, List<ToolCall> tools, ExecutionContext context, String agentName, String agentId) {
        return subAgentAssembler.buildAgent(config, tools, context, agentName, null, null, agentId);
    }

    public Agent buildAgent(SessionConfig config, List<ToolCall> tools, ExecutionContext context, String agentName, Map<String, Object> extraSystemVars, String agentId) {
        return subAgentAssembler.buildAgent(config, tools, context, agentName, extraSystemVars, null, agentId);
    }
}
