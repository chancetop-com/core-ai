package ai.core.flow;

/**
 * @author stephen
 */
public enum FlowNodeType {
    TRIGGER,
    TOOL,
    AGENT,
    AGENT_GROUP,
    LLM,
    AGENT_TOOL,
    RAG,
    HANDOFF,
    OPERATOR_IF,
    OPERATOR_SWITCH,
    OPERATOR_FILTER,
    EMPTY,
    EXECUTE,
    STOP
}
