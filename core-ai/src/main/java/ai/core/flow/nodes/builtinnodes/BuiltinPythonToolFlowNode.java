package ai.core.flow.nodes.builtinnodes;

import ai.core.defaultagents.DefaultPythonScriptAgent;
import ai.core.flow.nodes.FunctionToolFlowNode;

import java.util.UUID;

/**
 * @author stephen
 */
public class BuiltinPythonToolFlowNode {
    public static FunctionToolFlowNode of() {
        return new FunctionToolFlowNode(UUID.randomUUID().toString(),
                DefaultPythonScriptAgent.PYTHON_AGENT_NAME,
                DefaultPythonScriptAgent.pythonScriptTool());
    }
}
