package ai.core.cli.acp;

import ai.core.llm.streaming.StreamingCallback;
import ai.core.llm.domain.FunctionCall;
import com.agentclientprotocol.sdk.agent.SyncPromptContext;
import com.agentclientprotocol.sdk.spec.AcpSchema;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ACP streaming callback — forwards core-ai Agent streaming output as ACP session updates.
 */
class AcpStreamingCallback implements StreamingCallback {

    private final SyncPromptContext ctx;
    private final String sessionId;
    private final AtomicReference<StringBuilder> outputRef;
    private final AtomicReference<StringBuilder> thoughtRef;

    AcpStreamingCallback(SyncPromptContext ctx, String sessionId,
                         AtomicReference<StringBuilder> outputRef,
                         AtomicReference<StringBuilder> thoughtRef) {
        this.ctx = ctx;
        this.sessionId = sessionId;
        this.outputRef = outputRef;
        this.thoughtRef = thoughtRef;
    }

    @Override
    public void onChunk(String chunk) {
        var sb = outputRef.get();
        if (sb != null) {
            sb.append(chunk);
        }
        ctx.sendUpdate(sessionId,
                new AcpSchema.AgentMessageChunk("agent_message_chunk",
                        new AcpSchema.TextContent(chunk)));
    }

    @Override
    public void onReasoningChunk(String chunk) {
        var sb = thoughtRef.get();
        if (sb != null) {
            sb.append(chunk);
        }
        ctx.sendUpdate(sessionId,
                new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
                        new AcpSchema.TextContent(chunk)));
    }

    @Override
    public void onComplete() {
        // Flush any remaining thought
        var thought = thoughtRef.getAndSet(null);
        if (thought != null && !thought.isEmpty()) {
            ctx.sendUpdate(sessionId,
                    new AcpSchema.AgentThoughtChunk("agent_thought_chunk",
                            new AcpSchema.TextContent(thought.toString())));
        }
    }

    @Override
    public void onError(Throwable error) {
        ctx.sendMessage("Error: " + error.getMessage());
    }

    @Override
    public void onTool(List<FunctionCall> functionCalls) {
        for (var fc : functionCalls) {
            String args = fc.function != null ? fc.function.arguments : "{}";
            ctx.sendUpdate(sessionId,
                    new AcpSchema.ToolCall("tool_call", fc.id, fc.function != null ? fc.function.name : "tool",
                            AcpSchema.ToolKind.OTHER, AcpSchema.ToolCallStatus.IN_PROGRESS,
                            List.of(new AcpSchema.ToolCallContentBlock(
                                    "content", new AcpSchema.TextContent(args))),
                            null, args, null, null));
        }
    }

    @Override
    public void onToolComplete(List<FunctionCall> functionCalls) {
        for (var fc : functionCalls) {
            ctx.sendUpdate(sessionId,
                    new AcpSchema.ToolCall("tool_call", fc.id, fc.function != null ? fc.function.name : "tool",
                            AcpSchema.ToolKind.OTHER, AcpSchema.ToolCallStatus.COMPLETED,
                            null, null, null, null, null));
        }
    }
}
