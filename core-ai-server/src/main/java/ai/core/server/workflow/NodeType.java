package ai.core.server.workflow;

/**
 * The node taxonomy (Dify-aligned). The engine is type-agnostic — it keeps the type only as a label on
 * WorkflowNode; only {@link NodeExecutorRegistry} interprets it to pick an executor. NOTE is canvas-only and
 * excluded from the executable graph at parse time.
 *
 * @author Xander
 */
public enum NodeType {
    START,
    END,
    AGENT,
    LLM,
    CODE,
    HTTP,
    IF_ELSE,
    AGGREGATOR,
    TEMPLATE,
    MCP_TOOL,
    API_TOOL,
    HUMAN_INPUT,
    WORKFLOW,
    NOTE;
    // todo: ITERATION / LOOP container node types are deferred (P3) — re-add with the container-scope engine work.

    public static NodeType of(String type) {
        try {
            return NodeType.valueOf(type);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException("unknown workflow node type: " + type, e);
        }
    }
}
