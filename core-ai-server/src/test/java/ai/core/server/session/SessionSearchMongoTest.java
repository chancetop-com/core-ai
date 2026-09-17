package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.workflow.WorkflowTestModule;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the search against a real Mongo, which has notablescan enabled — the query shape has to be
 * backed by the indexes that already exist on chat_sessions and chat_messages.
 *
 * @author stephen
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class SessionSearchMongoTest {
    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<ChatMessage> chatMessageCollection;

    private final String userId = "search-user-" + UUID.randomUUID();
    private final List<String> sessionIds = new ArrayList<>();
    private long messageSeq;

    @AfterEach
    void cleanup() {
        if (!sessionIds.isEmpty()) {
            chatMessageCollection.delete(Filters.in("session_id", sessionIds));
            chatSessionCollection.delete(Filters.in("_id", sessionIds));
        }
    }

    @Test
    void findsTheConversationAndItsExcerpt() {
        var sessionId = session("terraform state migration", "agent-1", ZonedDateTime.now().minusDays(3));
        message(sessionId, "user", "the terraform state file is in the ops bucket", ZonedDateTime.now().minusDays(3));

        var hits = search("terraform", "agent-1");

        assertEquals(1, hits.size());
        assertEquals(sessionId, hits.getFirst().sessionId);
        assertEquals("terraform state migration", hits.getFirst().title);
        assertTrue(hits.getFirst().snippets.getFirst().text.contains("terraform state file"));
    }

    @Test
    void matchesChineseKeywords() {
        var sessionId = session("知识库设计", "agent-1", ZonedDateTime.now().minusDays(1));
        message(sessionId, "user", "把知识库的检索改成关键词匹配", ZonedDateTime.now().minusDays(1));

        var hits = search("知识库检索", "agent-1");

        assertEquals(1, hits.size());
        assertEquals(sessionId, hits.getFirst().sessionId);
    }

    @Test
    void anotherUsersConversationIsInvisible() {
        var sessionId = session("terraform of someone else", "agent-1", ZonedDateTime.now().minusDays(1));
        message(sessionId, "user", "terraform elsewhere", ZonedDateTime.now().minusDays(1));

        var hits = service().search(request("another-user", "agent-1", "terraform"));

        assertEquals(List.of(), hits);
    }

    @Test
    void deletedTalkIsInvisible() {
        var sessionId = session("terraform deleted", "agent-1", ZonedDateTime.now().minusDays(1));
        message(sessionId, "user", "terraform deleted talk", ZonedDateTime.now().minusDays(1));
        var stored = chatSessionCollection.get(sessionId).orElseThrow();
        stored.deletedAt = ZonedDateTime.now();
        chatSessionCollection.replace(stored);

        assertEquals(List.of(), search("terraform", "agent-1"));
    }

    @Test
    void conversationOlderThanTheWindowIsInvisible() {
        var sessionId = session("terraform long ago", "agent-1", ZonedDateTime.now().minusDays(30));
        message(sessionId, "user", "terraform long ago", ZonedDateTime.now().minusDays(30));

        assertEquals(List.of(), service().search(request(userId, "agent-1", "terraform", 7)));

        var hits = service().search(request(userId, "agent-1", "terraform", 90));
        assertEquals(1, hits.size());
    }

    @Test
    void currentConversationIsInvisible() {
        var sessionId = session("terraform now", "agent-1", ZonedDateTime.now());
        message(sessionId, "user", "terraform now", ZonedDateTime.now());
        var request = request(userId, "agent-1", "terraform");
        request.currentSessionId = sessionId;

        assertEquals(List.of(), service().search(request));
    }

    @Test
    void otherAgentConversationIsInvisibleUnlessTheScopeIsWidened() {
        var sessionId = session("terraform on another agent", "agent-1", ZonedDateTime.now());
        message(sessionId, "user", "terraform on another agent", ZonedDateTime.now());

        assertEquals(List.of(), service().search(request(userId, "agent-2", "terraform", 90)));

        var widened = request(userId, null, "terraform");
        assertEquals(1, service().search(widened).size());
    }

    @Test
    void pinningAnotherUsersConversationLeaksNothing() {
        var sessionId = session("terraform of someone else", "agent-1", ZonedDateTime.now());
        message(sessionId, "user", "terraform detail", ZonedDateTime.now());
        var request = request("another-user", "agent-1", "terraform");
        request.sessionId = sessionId;

        assertEquals(List.of(), service().search(request));
    }

    @Test
    void pinnedConversationReturnsAllOfItsExcerpts() {
        var sessionId = session("terraform many", "agent-1", ZonedDateTime.now());
        for (var index = 0; index < 6; index++) {
            message(sessionId, "user", "terraform line " + index, ZonedDateTime.now().minusMinutes(index));
        }
        var request = request(userId, "agent-1", "terraform");
        request.sessionId = sessionId;

        var hit = service().search(request).getFirst();

        assertEquals(6, hit.matchCount);
        assertEquals(6, hit.snippets.size());
    }

    @Test
    void conversationWithoutAMatchingMessageIsNotReported() {
        var sessionId = session("unrelated talk", "agent-1", ZonedDateTime.now());
        message(sessionId, "user", "nothing of interest", ZonedDateTime.now());

        assertEquals(List.of(), search("terraform", "agent-1"));
    }

    private SessionSearchService service() {
        var service = new SessionSearchService();
        service.chatSessionCollection = chatSessionCollection;
        service.chatMessageCollection = chatMessageCollection;
        return service;
    }

    private List<SessionSearchHit> search(String query, String agentId) {
        return service().search(request(userId, agentId, query));
    }

    private SessionSearchQuery request(String userId, String agentId, String query) {
        return request(userId, agentId, query, SessionSearchService.DEFAULT_WINDOW_DAYS);
    }

    private SessionSearchQuery request(String userId, String agentId, String query, int windowDays) {
        var request = new SessionSearchQuery();
        request.userId = userId;
        request.agentId = agentId;
        request.query = query;
        request.windowDays = windowDays;
        return request;
    }

    private String session(String title, String agentId, ZonedDateTime lastMessageAt) {
        var session = new ChatSession();
        session.id = "search-session-" + UUID.randomUUID();
        session.userId = userId;
        session.agentId = agentId;
        session.source = "chat";
        session.title = title;
        session.messageCount = 1L;
        session.createdAt = lastMessageAt;
        session.lastMessageAt = lastMessageAt;
        chatSessionCollection.insert(session);
        sessionIds.add(session.id);
        return session.id;
    }

    private void message(String sessionId, String role, String content, ZonedDateTime createdAt) {
        var message = new ChatMessage();
        message.id = "search-message-" + UUID.randomUUID();
        message.sessionId = sessionId;
        message.seq = messageSeq++;
        message.role = role;
        message.content = content;
        message.createdAt = createdAt;
        chatMessageCollection.insert(message);
    }
}
