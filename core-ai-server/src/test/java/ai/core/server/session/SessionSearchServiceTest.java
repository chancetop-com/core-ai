package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.mongo.impl.ZonedDateTimeCodec;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionSearchServiceTest {
    private static final CodecRegistry CODECS = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs(new ZonedDateTimeCodec()),
            MongoClientSettings.getDefaultCodecRegistry());
    @SuppressWarnings("unchecked")
    private final MongoCollection<ChatSession> chatSessionCollection = mock(MongoCollection.class);
    @SuppressWarnings("unchecked")
    private final MongoCollection<ChatMessage> chatMessageCollection = mock(MongoCollection.class);
    private final SessionSearchService service = service();
    private final ZonedDateTime now = ZonedDateTime.now();

    @Test
    void withoutAnOwnerOrKeywordsNothingIsQueried() {
        assertEquals(List.of(), service.search(search("terraform", null)));
        assertEquals(List.of(), service.search(search("terraform", "  ")));
        assertEquals(List.of(), service.search(search("  ", "user-1")));
        assertEquals(List.of(), service.search(search("the and for", "user-1")));

        verify(chatSessionCollection, never()).find(any(Query.class));
    }

    @Test
    void noCandidateConversationSkipsTheMessageQuery() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of());

        assertEquals(List.of(), service.search(search("terraform", "user-1")));

        verify(chatMessageCollection, never()).find(any(Query.class));
    }

    @Test
    void candidateConversationsAreScopedToOwnerAgentAndWindow() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of());
        var request = search("terraform", "user-1");
        request.agentId = "agent-1";

        service.search(request);

        var filter = capturedSessionFilter();
        assertEquals("user-1", filter.get("user_id").asString().getValue());
        assertEquals("agent-1", filter.get("agent_id").asString().getValue());
        assertTrue(filter.containsKey("deleted_at"), "a deleted conversation must stay out of the search");
        assertTrue(filter.containsKey("last_message_at"), "without a bound window the scan has no anchor");
        assertFalse(filter.containsKey("_id"), "a plain search looks at every conversation of the agent");
    }

    @Test
    void widenedSearchDropsTheAgentScopeOnly() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of());

        service.search(search("terraform", "user-1"));

        assertFalse(capturedSessionFilter().containsKey("agent_id"));
    }

    @Test
    void drillDownPinsOneConversationById() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of(session("session-2", "old chat", now.minusDays(1))));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(message("session-2", "user", "terraform notes", now.minusDays(1))));
        var request = search("terraform", "user-1");
        request.sessionId = "session-2";

        service.search(request);

        var filter = capturedSessionFilter();
        assertEquals("session-2", filter.get("_id").asString().getValue());
        assertFalse(filter.containsKey("last_message_at"), "a pinned conversation is searched whatever its age");
    }

    @Test
    void onlyConversationsWithAMatchingMessageAreReported() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of(
                session("session-1", "terraform run", now.minusMinutes(10)),
                session("session-2", "other chat", now.minusDays(2))));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(
                message("session-1", "user", "apply the terraform stack", now.minusMinutes(12)),
                message("session-2", "assistant", "unrelated answer", now.minusDays(2))));

        var hits = service.search(search("terraform", "user-1"));

        assertEquals(1, hits.size());
        assertEquals("session-1", hits.getFirst().sessionId);
        assertEquals("terraform run", hits.getFirst().title);
        assertEquals(1, hits.getFirst().matchCount);
        assertEquals(now.minusMinutes(10), hits.getFirst().lastMessageAt);
        assertEquals("user", hits.getFirst().snippets.getFirst().role);
        assertTrue(hits.getFirst().snippets.getFirst().text.contains("terraform stack"));
    }

    @Test
    void currentConversationIsExcluded() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of(
                session("session-current", "current chat", now),
                session("session-old", "old chat", now.minusDays(3))));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(
                message("session-current", "user", "terraform now", now),
                message("session-old", "user", "terraform before", now.minusDays(3))));
        var request = search("terraform", "user-1");
        request.currentSessionId = "session-current";

        var hits = service.search(request);

        assertEquals(1, hits.size());
        assertEquals("session-old", hits.getFirst().sessionId);
    }

    @Test
    void conversationsRankByMatchCountThenRecency() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of(
                session("session-old-many", "many", now.minusDays(5)),
                session("session-recent", "recent", now)));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(
                message("session-old-many", "user", "terraform and aks again", now.minusDays(5)),
                message("session-old-many", "assistant", "the aks terraform plan", now.minusDays(5)),
                message("session-recent", "user", "terraform only", now)));

        var hits = service.search(search("terraform aks", "user-1"));

        assertEquals(List.of("session-old-many", "session-recent"), hits.stream().map(hit -> hit.sessionId).toList());
        assertEquals(2, hits.getFirst().matchCount);
        assertEquals(1, hits.getLast().matchCount);
    }

    @Test
    void limitCapsTheReturnedConversations() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of(
                session("session-1", "one", now),
                session("session-2", "two", now.minusMinutes(1))));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(
                message("session-1", "user", "terraform", now),
                message("session-2", "user", "terraform", now.minusMinutes(1))));
        var request = search("terraform", "user-1");
        request.limit = 1;

        assertEquals(List.of("session-1"), service.search(request).stream().map(hit -> hit.sessionId).toList());
    }

    @Test
    void pinnedConversationReturnsMoreExcerptsThanAPlainSearch() {
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of(session("session-1", "many matches", now)));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(messages("session-1", 8));

        var hits = service.search(search("terraform", "user-1"));
        assertEquals(8, hits.getFirst().matchCount);
        assertEquals(3, hits.getFirst().snippets.size());

        var pinned = search("terraform", "user-1");
        pinned.sessionId = "session-1";

        assertEquals(8, service.search(pinned).getFirst().snippets.size());
    }

    @Test
    void everyExcerptIsShortenedAroundTheMatch() {
        var longLine = "terraform".repeat(200);
        when(chatSessionCollection.find(any(Query.class))).thenReturn(List.of(session("session-1", "long", now)));
        when(chatMessageCollection.find(any(Query.class))).thenReturn(List.of(message("session-1", "user", longLine, now)));

        var snippet = service.search(search("terraform", "user-1")).getFirst().snippets.getFirst();

        assertEquals(81, snippet.text.length());
        assertTrue(snippet.text.endsWith("…"));
    }

    private SessionSearchService service() {
        var service = new SessionSearchService();
        service.chatSessionCollection = chatSessionCollection;
        service.chatMessageCollection = chatMessageCollection;
        return service;
    }

    private SessionSearchQuery search(String text, String userId) {
        var request = new SessionSearchQuery();
        request.query = text;
        request.userId = userId;
        return request;
    }

    private Map<String, BsonValue> capturedSessionFilter() {
        var captor = ArgumentCaptor.forClass(Query.class);
        verify(chatSessionCollection, atLeastOnce()).find(captor.capture());
        var document = captor.getValue().filter.toBsonDocument(BsonDocument.class, CODECS);
        var flat = new HashMap<String, BsonValue>();
        if (document.containsKey("$and")) {
            document.getArray("$and").forEach(clause -> clause.asDocument().forEach(flat::put));
        } else {
            document.forEach(flat::put);
        }
        return flat;
    }

    private List<ChatMessage> messages(String sessionId, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> message(sessionId, "user", "terraform " + index, now.minusMinutes(index)))
                .toList();
    }

    private ChatSession session(String id, String title, ZonedDateTime lastMessageAt) {
        var session = new ChatSession();
        session.id = id;
        session.userId = "user-1";
        session.agentId = "agent-1";
        session.title = title;
        session.lastMessageAt = lastMessageAt;
        return session;
    }

    private ChatMessage message(String sessionId, String role, String content, ZonedDateTime createdAt) {
        var message = new ChatMessage();
        message.sessionId = sessionId;
        message.role = role;
        message.content = content;
        message.createdAt = createdAt;
        return message;
    }
}
