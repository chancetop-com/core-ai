package ai.core.tool.wait;

import ai.core.tool.ToolCall;

import java.util.Map;

/**
 * A tool whose wait action is a runtime control request, not an ordinary bounded query. The
 * runtime recognizes the request by the tool's own typed parameters (never by matching a tool
 * name string) and parks the calling turn until the task completes.
 *
 * @author stephen
 */
public interface RuntimeWaitTool {
    /** Returns null for every action that is not a wait (poll, cancel, malformed arguments). */
    WaitRequest prepareWait(Map<String, Object> args);

    /** Pure classification used by the orchestration before any execution side effect. */
    static boolean isWait(ToolCall tool, Map<String, Object> args) {
        return tool instanceof RuntimeWaitTool waitTool && waitTool.prepareWait(args) != null;
    }
}
