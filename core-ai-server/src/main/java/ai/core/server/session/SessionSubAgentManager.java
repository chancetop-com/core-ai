package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.api.server.session.SessionConfig;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.util.IdLists;
import ai.core.session.InProcessAgentSession;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.registry.ToolRegistryFactory;
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
        var subAgentIds = definition.publishedConfig != null && definition.publishedConfig.subAgentIds != null
                ? definition.publishedConfig.subAgentIds
                : definition.subAgentIds;
        var cleanSubAgentIds = IdLists.clean(subAgentIds);
        var subAgents = subAgentAssembler.assemble(cleanSubAgentIds, session.id());
        var names = new ArrayList<String>();
        for (var subAgent : subAgents) {
            session.loadTools(List.of(subAgent));
            names.add(subAgent.getName());
        }
        chatMessageService.addLoadedSubAgentIds(session.id(), cleanSubAgentIds);
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

    public ToolRegistry resolveToolsToRegistry(AgentDefinition definition, String sessionId) {
        return subAgentAssembler.resolveToolsToRegistry(definition, sessionId);
    }

    public SessionConfig toSessionConfig(AgentDefinition definition) {
        return subAgentAssembler.toSessionConfig(definition);
    }

    public Agent buildAgent(SessionConfig config, ToolRegistry toolRegistry, ExecutionContext context,
                            String agentName, Map<String, Object> extraSystemVars, String agentId,
                            List<AbstractLifecycle> extraLifecycles) {
        return subAgentAssembler.buildAgent(config, toolRegistry, context, agentName, extraSystemVars, agentId, extraLifecycles);
    }

    public static ToolRegistry toolsToRegistry(List<ToolCall> tools) {
        var registry = ToolRegistryFactory.createEmpty();
        if (tools != null && !tools.isEmpty()) {
            registry.registerProvider(new ListToolProvider("session-tools", tools));
        }
        return registry;
    }
}
