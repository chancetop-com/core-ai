package ai.core.cli.appserver;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendSessionMessageToolTest {

    private static ExecutionContext context(String sessionId) {
        return ExecutionContext.builder().sessionId(sessionId).build();
    }

    @Test
    void forwardsToAnotherSessionAndReturnsTurnId() {
        var captured = new AtomicReference<String>();
        var tool = SendSessionMessageTool.create((sessionId, message, turnId) -> {
            captured.set(sessionId + "|" + message + "|" + turnId);
            return new SendSessionMessageTool.Forwarded(sessionId, "idle", false, turnId);
        });

        var result = tool.execute("""
                {"session_id":"cli-target","message":"continue please"}
                """, context("cli-current"));

        assertTrue(result.isCompleted());
        assertTrue(result.getResult().contains("cli-target"));
        assertTrue(result.getResult().contains("turn_id:"));
        assertTrue(captured.get().startsWith("cli-target|continue please|"));
    }

    @Test
    void rejectsTheCurrentSession() {
        var tool = SendSessionMessageTool.create((sessionId, message, turnId) -> {
            throw new AssertionError("must not forward to the current session");
        });

        var result = tool.execute("""
                {"session_id":"cli-current","message":"hello"}
                """, context("cli-current"));

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("must differ from the current session"));
    }

    @Test
    void rejectsMissingArguments() {
        var tool = SendSessionMessageTool.create((sessionId, message, turnId) -> {
            throw new AssertionError("must not forward incomplete arguments");
        });

        var missingSession = tool.execute("""
                {"message":"hello"}
                """, context("cli-current"));
        var blankMessage = tool.execute("""
                {"session_id":"cli-target","message":"   "}
                """, context("cli-current"));

        assertTrue(missingSession.isFailed());
        assertTrue(missingSession.getResult().contains("session_id is required"));
        assertTrue(blankMessage.isFailed());
        assertTrue(blankMessage.getResult().contains("message is required"));
    }

    @Test
    void surfacesForwarderBusinessError() {
        var tool = SendSessionMessageTool.create((sessionId, message, turnId) -> {
            throw RpcException.business("SESSION_BUSY", "session is in use by another process");
        });

        var result = tool.execute("""
                {"session_id":"cli-target","message":"hello"}
                """, context("cli-current"));

        assertFalse(result.isCompleted());
        assertTrue(result.getResult().contains("SESSION_BUSY"));
        assertTrue(result.getResult().contains("another process"));
    }

    @Test
    void requiresExecutionContext() {
        var tool = SendSessionMessageTool.create((sessionId, message, turnId) -> {
            throw new AssertionError("must not forward without context");
        });

        ToolCallResult result = tool.execute("""
                {"session_id":"cli-target","message":"hello"}
                """);

        assertTrue(result.isFailed());
        assertEquals("send_session_message requires execution context", result.getResult());
    }
}
