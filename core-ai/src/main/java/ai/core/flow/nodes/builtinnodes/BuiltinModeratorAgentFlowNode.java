package ai.core.flow.nodes.builtinnodes;

import ai.core.agent.formatter.FormatterType;
import ai.core.defaultagents.DefaultModeratorAgent;
import ai.core.flow.nodes.AgentFlowNode;

import java.util.UUID;

/**
 * @author stephen
 */
public class BuiltinModeratorAgentFlowNode {
    public static AgentFlowNode of() {
        var node = new AgentFlowNode(UUID.randomUUID().toString(),
                DefaultModeratorAgent.DEFAULT_MODERATOR_AGENT_NAME,
                DefaultModeratorAgent.DEFAULT_MODERATOR_AGENT_DESCRIPTION,
                DefaultModeratorAgent.defaultModeratorSystemPrompt(null),
                DefaultModeratorAgent.defaultModeratorPromptTemplate(null));
        node.setUseGroupContext(true);
        node.setFormatter(FormatterType.JSON.name());
        return node;
    }
}
