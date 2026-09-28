package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.server.session.SessionReadService;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadSessionToolTest {
    private final SessionReadService sessionReadService = mock(SessionReadService.class);
    private final ReadSessionTool tool = new ReadSessionTool(sessionReadService);
    private final ExecutionContext context = ExecutionContext.builder().sessionId("session-current").userId("user-1").build();

    @Test
    void requiresASessionId() {
        var result = tool.execute("{}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("session_id is required"));
    }

    @Test
    void requiresAUserSession() {
        var result = tool.execute("{\"session_id\":\"s-1\"}", ExecutionContext.builder().sessionId("session-current").build());

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("needs a user session"));
    }

    @Test
    void directExecuteWithoutContextIsRejected() {
        var result = tool.execute("{\"session_id\":\"s-1\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("requires ExecutionContext"));
    }

    @Test
    void aConversationOfSomeoneElseReadsAsUnknown() {
        when(sessionReadService.read(anyString(), anyString(), anyInt())).thenReturn(null);

        var result = tool.execute("{\"session_id\":\"s-1\"}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("No such conversation belongs to this user"), result.getResult());
    }

    @Test
    void readsWithTheCallersIdentityAndTheRequestedTail() {
        when(sessionReadService.read(anyString(), anyString(), anyInt())).thenReturn(read());

        tool.execute("{\"session_id\":\"s-1\",\"tail\":12}", context);

        var userId = ArgumentCaptor.forClass(String.class);
        var sessionId = ArgumentCaptor.forClass(String.class);
        var tail = ArgumentCaptor.forClass(Integer.class);
        verify(sessionReadService).read(userId.capture(), sessionId.capture(), tail.capture());
        assertEquals("user-1", userId.getValue());
        assertEquals("s-1", sessionId.getValue());
        assertEquals(12, tail.getValue());
    }

    @Test
    void rendersWhatHappenedAndWhatWasProduced() {
        when(sessionReadService.read(anyString(), anyString(), anyInt())).thenReturn(read());

        var result = tool.execute("{\"session_id\":\"s-1\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        var text = result.getResult();
        assertTrue(text.contains("Nightly report"), text);
        assertTrue(text.contains("agent-1"), text);
        assertTrue(text.contains("[user"));
        assertTrue(text.contains("publish it?"), text);
        assertTrue(text.contains("draft is ready"), text);
        assertTrue(text.contains("tools: submit_artifacts"), text);
        assertTrue(text.contains("draft.html (f-1)"), text);
        assertTrue(text.contains("earlier ones are not shown"), text);
    }

    @Test
    void anEmptyConversationSaysSoInsteadOfShowingNothing() {
        var read = read();
        read.messages = List.of();
        when(sessionReadService.read(anyString(), anyString(), anyInt())).thenReturn(read);

        var result = tool.execute("{\"session_id\":\"s-1\"}", context);

        assertTrue(result.getResult().contains("no messages yet"), result.getResult());
        assertFalse(result.getResult().contains("earlier ones are not shown"));
    }

    private SessionReadService.SessionRead read() {
        var read = new SessionReadService.SessionRead();
        read.title = "Nightly report";
        read.agentId = "agent-1";
        read.lastMessageAt = ZonedDateTime.parse("2026-09-28T07:59:00Z");
        read.artifacts = List.of("draft.html (f-1)");
        read.moreMessages = true;
        var user = new SessionReadService.SessionRead.Line();
        user.role = "user";
        user.text = "publish it?";
        user.at = ZonedDateTime.parse("2026-09-28T07:58:00Z");
        var agent = new SessionReadService.SessionRead.Line();
        agent.role = "agent";
        agent.text = "draft is ready";
        agent.tools = List.of("submit_artifacts");
        agent.at = ZonedDateTime.parse("2026-09-28T07:59:00Z");
        read.messages = List.of(user, agent);
        return read;
    }
}
