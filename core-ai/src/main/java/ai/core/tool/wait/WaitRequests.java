package ai.core.tool.wait;

import ai.core.tool.ToolCall;

public final class WaitRequests {
    /** Null when the tool is not a runtime wait or the arguments do not describe one. */
    public static WaitRequest prepare(ToolCall tool, String arguments) {
        if (!(tool instanceof RuntimeWaitTool waitTool)) return null;
        try {
            return waitTool.prepareWait(tool.parseArguments(arguments));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private WaitRequests() {
    }
}
