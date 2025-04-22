package ai.core.flow.nodes.builtinnodes;

import ai.core.agent.formatter.FormatterType;
import ai.core.defaultagents.DefaultPythonScriptAgent;
import ai.core.flow.nodes.AgentFlowNode;

import java.util.UUID;

/**
 * @author stephen
 */
public class BuiltinPythonAgentFlowNode {
    public static AgentFlowNode of(String additionSystemPrompt) {
        var node = new AgentFlowNode(UUID.randomUUID().toString(),
                DefaultPythonScriptAgent.PYTHON_AGENT_NAME,
                DefaultPythonScriptAgent.PYTHON_AGENT_DESCRIPTION,
                DefaultPythonScriptAgent.defaultPythonScriptAgentSystemPrompt(additionSystemPrompt),
                DefaultPythonScriptAgent.defaultPythonScriptAgentPromptTemplate(null));
        node.setFormatter(FormatterType.CODE.name());
        return node;
    }
}
