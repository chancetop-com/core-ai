package ai.core.agent.subagent;

/**
 * Which tool owns a subagent thread. Threads of different kinds share the session registry and its
 * storage, but only their owning tool may continue them.
 *
 * @author stephen
 */
public enum SubagentThreadKind {
    /** Created by the task tool ({@code ai.core.tool.tools.TaskTool}). */
    TASK,
    /** A singleton subagent tool (SubAgentToolCall) that keeps its context across calls. */
    AGENT_TOOL
}
