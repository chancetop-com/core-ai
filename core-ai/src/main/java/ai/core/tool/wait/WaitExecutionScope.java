package ai.core.tool.wait;

import ai.core.agent.CancellationToken;
import ai.core.agent.ExecutionContext;

/** Captured at the tool-call boundary; never read back from a context a subagent may have rewritten. */
public record WaitExecutionScope(String ownerScope, String toolCallId, CancellationToken cancellationToken) {
    public static WaitExecutionScope of(ExecutionContext context, String toolCallId) {
        return new WaitExecutionScope(context.getSessionId(), toolCallId,
                context.getCancellationToken());
    }
}
