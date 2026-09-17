package ai.core.server.memory;

import ai.core.agent.ExecutionContext;
import ai.core.server.session.SessionSearchHit;
import ai.core.server.session.SessionSearchQuery;
import ai.core.server.session.SessionSearchService;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchSessionsToolTest {
    private final SessionSearchService sessionSearchService = mock(SessionSearchService.class);
    private final SearchSessionsTool tool = new SearchSessionsTool("assistant:user-1", sessionSearchService);
    private final ExecutionContext context = ExecutionContext.builder().sessionId("session-current").userId("user-1").build();

    @Test
    void requiresQuery() {
        var result = tool.execute("{}", context);

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("query is required"));
    }

    @Test
    void requiresAUserSession() {
        var result = tool.execute("{\"query\":\"terraform\"}", ExecutionContext.builder().sessionId("session-1").build());

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("needs a user session"));
    }

    @Test
    void directExecuteWithoutContextIsRejected() {
        var result = tool.execute("{\"query\":\"terraform\"}");

        assertEquals(ToolCallResult.Status.FAILED, result.getStatus());
        assertTrue(result.getResult().contains("requires ExecutionContext"));
    }

    @Test
    void searchIsScopedToTheCallerAndTheCurrentConversation() {
        when(sessionSearchService.search(any())).thenReturn(List.of());

        tool.execute("{\"query\":\"terraform\",\"limit\":9,\"window_days\":30}", context);

        var captor = ArgumentCaptor.forClass(SessionSearchQuery.class);
        verify(sessionSearchService).search(captor.capture());
        var request = captor.getValue();
        assertEquals("user-1", request.userId);
        assertEquals("assistant:user-1", request.agentId);
        assertEquals("session-current", request.currentSessionId);
        assertEquals("terraform", request.query);
        assertEquals(9, request.limit);
        assertEquals(30, request.windowDays);
    }

    @Test
    void defaultsAreAppliedWhenTheModelOmitsThem() {
        when(sessionSearchService.search(any())).thenReturn(List.of());

        tool.execute("{\"query\":\"terraform\"}", context);

        var captor = ArgumentCaptor.forClass(SessionSearchQuery.class);
        verify(sessionSearchService).search(captor.capture());
        assertEquals(SessionSearchService.DEFAULT_LIMIT, captor.getValue().limit);
        assertEquals(SessionSearchService.DEFAULT_WINDOW_DAYS, captor.getValue().windowDays);
    }

    @Test
    void matchesAreRenderedWithSessionIdAndExcerpts() {
        when(sessionSearchService.search(any())).thenReturn(List.of(hit()));

        var result = tool.execute("{\"query\":\"terraform\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        var text = result.getResult();
        assertTrue(text.contains("Found 1 conversation"));
        assertTrue(text.contains("terraform state migration"));
        assertTrue(text.contains("session_id: session-9"));
        assertTrue(text.contains("[user 2026-09-17T09:00Z] the terraform plan is ready"), text);
        assertTrue(text.contains("(showing 1 of 4)"));
        assertTrue(text.contains("excerpts, not whole conversations"));
    }

    @Test
    void emptyResultTellsTheModelHowToRetry() {
        when(sessionSearchService.search(any())).thenReturn(List.of());

        var result = tool.execute("{\"query\":\"terraform\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("No past conversation matched"));
        assertTrue(result.getResult().contains("window_days"));
    }

    @Test
    void pinnedSearchReportsHowManyMessagesMatched() {
        when(sessionSearchService.search(any())).thenReturn(List.of(hit()));

        var result = tool.execute("{\"query\":\"terraform\",\"session_id\":\"session-9\"}", context);

        assertEquals(ToolCallResult.Status.COMPLETED, result.getStatus());
        assertTrue(result.getResult().contains("Found 4 matching messages"));
        assertTrue(result.getResult().contains("in this conversation"));
        var captor = ArgumentCaptor.forClass(SessionSearchQuery.class);
        verify(sessionSearchService).search(captor.capture());
        assertEquals("session-9", captor.getValue().sessionId);
    }

    private SessionSearchHit hit() {
        var hit = new SessionSearchHit();
        hit.sessionId = "session-9";
        hit.title = "terraform state migration";
        hit.lastMessageAt = ZonedDateTime.parse("2026-09-17T09:05:00Z");
        hit.matchCount = 4;
        var snippet = new SessionSearchHit.Snippet();
        snippet.role = "user";
        snippet.createdAt = ZonedDateTime.parse("2026-09-17T09:00:00Z");
        snippet.text = "the terraform plan is ready";
        hit.snippets.add(snippet);
        return hit;
    }
}
