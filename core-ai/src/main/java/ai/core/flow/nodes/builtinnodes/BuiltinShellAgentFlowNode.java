package ai.core.flow.nodes.builtinnodes;

import ai.core.agent.formatter.FormatterType;
import ai.core.defaultagents.DefaultShellCommandAgent;
import ai.core.flow.nodes.AgentFlowNode;

import java.util.UUID;

/**
 * @author stephen
 */
public class BuiltinShellAgentFlowNode {
    public static AgentFlowNode of() {
        var node = new AgentFlowNode(UUID.randomUUID().toString(),
                DefaultShellCommandAgent.SHELL_COMMAND_AGENT_NAME,
                DefaultShellCommandAgent.SHELL_COMMAND_AGENT_DESCRIPTION,
                DefaultShellCommandAgent.defaultShellAgentSystemPrompt(null),
                DefaultShellCommandAgent.defaultShellAgentPromptTemplate(null));
        node.setFormatter(FormatterType.CODE.name());
        return node;
    }
}
