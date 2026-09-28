package ai.core.server.session;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionReadServiceTest {
    private final SessionRegistry sessionRegistry = mock(SessionRegistry.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<ChatMessage> chatMessageCollection = mock(MongoCollection.class);
    private final SessionReadService service = new SessionReadService();

    @BeforeEach
    void wire() {
        service.sessionRegistry = sessionRegistry;
        service.chatMessageCollection = chatMessageCollection;
    }

    @Test
    void anotherUsersConversationIsNotEvenReportedAsExisting() {
        when(sessionRegistry.requireAccessible("s-1", "u-2")).thenThrow(new ForbiddenException("session is unavailable"));

        assertNull(service.read("u-2", "s-1", SessionReadService.DEFAULT_TAIL));
    }

    @Test
    void missingArgumentsReadNothing() {
        assertNull(service.read(null, "s-1", 6));
        assertNull(service.read("u-1", null, 6));
    }

    @Test
    void tailIsReturnedOldestFirstWithWhatEachTurnRan() {
        when(sessionRegistry.requireAccessible("s-1", "u-1")).thenReturn(session());
        var newest = message("agent", "draft is ready", 7L);
        newest.tools = List.of(tool("submit_artifacts"), tool("generate_image"));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(newest, message("user", "publish it?", 6L)));

        var read = service.read("u-1", "s-1", 2);

        assertEquals("Nightly report", read.title);
        assertEquals("agent-1", read.agentId);
        assertEquals(2, read.messages.size());
        assertEquals("publish it?", read.messages.getFirst().text);
        assertEquals("draft is ready", read.messages.getLast().text);
        assertEquals(List.of("submit_artifacts", "generate_image"), read.messages.getLast().tools);
        assertTrue(read.moreMessages, "a full tail means older messages were left behind");
    }

    @Test
    void tailIsClampedAndAskedForNewestFirst() {
        when(sessionRegistry.requireAccessible("s-1", "u-1")).thenReturn(session());
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of());

        service.read("u-1", "s-1", 500);

        var captor = ArgumentCaptor.forClass(Query.class);
        verify(chatMessageCollection).find(captor.capture());
        assertEquals(SessionReadService.MAX_TAIL, captor.getValue().limit);
    }

    @Test
    void anEmptyConversationStillNamesItself() {
        when(sessionRegistry.requireAccessible("s-1", "u-1")).thenReturn(session());
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of());

        var read = service.read("u-1", "s-1", 0);

        assertEquals("Nightly report", read.title);
        assertTrue(read.messages.isEmpty());
        assertEquals(1, read.artifacts.size());
        assertTrue(read.artifacts.getFirst().contains("draft.html"));
    }

    private ChatSession session() {
        var session = new ChatSession();
        session.id = "s-1";
        session.title = "Nightly report";
        session.agentId = "agent-1";
        session.lastMessageAt = ZonedDateTime.parse("2026-09-28T07:59:00Z");
        var artifact = new AgentRunArtifact();
        artifact.fileId = "f-1";
        artifact.fileName = "draft.html";
        session.artifacts = List.of(artifact);
        return session;
    }

    private ChatMessage message(String role, String content, Long seq) {
        var message = new ChatMessage();
        message.sessionId = "s-1";
        message.role = role;
        message.content = content;
        message.seq = seq;
        message.createdAt = ZonedDateTime.parse("2026-09-28T07:5" + seq + ":00Z");
        return message;
    }

    private ChatMessage.ToolCallRecord tool(String name) {
        var tool = new ChatMessage.ToolCallRecord();
        tool.name = name;
        return tool;
    }
}
