package ai.core.server.trace.service;

import ai.core.server.domain.User;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceServiceTest {
    @SuppressWarnings("unchecked")
    static MongoCollection<Trace> traceCollection() {
        return (MongoCollection<Trace>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    static MongoCollection<Span> spanCollection() {
        return (MongoCollection<Span>) mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    static MongoCollection<User> userCollection() {
        return (MongoCollection<User>) mock(MongoCollection.class);
    }

    static Trace trace(String id, String userId, String name, String agentName, String model, int minutesAgo) {
        var trace = new Trace();
        trace.id = id;
        trace.traceId = id;
        trace.userId = userId;
        trace.name = name;
        trace.agentName = agentName;
        trace.model = model;
        trace.totalTokens = 1L;
        trace.cachedTokens = 0L;
        trace.costUsd = 0.0;
        trace.createdAt = ZonedDateTime.now().minusMinutes(minutesAgo);
        return trace;
    }

    static User user(String name, String email) {
        var user = new User();
        user.id = email;
        user.email = email;
        user.name = name;
        return user;
    }

    @Test
    void listPlainTextQueryMatchesAccountNameThroughUserIdQuery() {
        var service = service();
        var aliceTrace = trace("t1", "alice@example.com", "old chat completion", "support-agent", "gpt-4o", 10);
        when(service.userCollection.find(any(Query.class))).thenReturn(List.of(
            user("Alice Chen", "alice@example.com"),
            user("Bob Li", "bob@example.com")));
        // First trace query is the indexed user_id match; second is the bounded name/agent scan.
        when(service.traceCollection.find(any(Query.class))).thenReturn(List.of(aliceTrace)).thenReturn(List.of());

        var filter = new TraceService.TraceListFilter();
        filter.q = "alice";
        filter.offset = 0;
        filter.limit = 20;

        assertEquals(List.of(aliceTrace), service.list(filter));
        verify(service.userCollection).find(any(Query.class));
    }

    @Test
    void facetsPlainTextQueryAggregatesInMemory() {
        var service = service();
        var first = trace("t1", "alice@example.com", "chat completion", "support-agent", "gpt-4o", 2);
        var second = trace("t2", "alice@example.com", "tool call", "support-agent", "gpt-4o", 1);
        var ignored = trace("t3", "bob@example.com", "chat completion", "support-agent", "gpt-4o-mini", 0);
        when(service.userCollection.find(any(Query.class))).thenReturn(List.of(user("Alice Chen", "alice@example.com")));
        when(service.traceCollection.find(any(Query.class))).thenReturn(List.of(first, second)).thenReturn(List.of(ignored));

        var filter = new TraceService.TraceListFilter();
        filter.q = "alice";

        var facets = service.facets("model", filter);

        assertEquals(1, facets.size());
        assertEquals("gpt-4o", facets.getFirst().get("value"));
        assertEquals(2, facets.getFirst().get("count"));
    }

    @Test
    void listAdvancedNameRegexFiltersAfterIndexedQuery() {
        var service = service();
        var match = trace("t1", "alice@example.com", "checkout flow", "support-agent", "gpt-4o", 1);
        var ignored = trace("t2", "alice@example.com", "chat completion", "support-agent", "gpt-4o", 0);
        when(service.traceCollection.find(any(Query.class))).thenReturn(List.of(match, ignored));

        var filter = new TraceService.TraceListFilter();
        filter.name = "checkout";
        filter.offset = 0;
        filter.limit = 20;

        assertEquals(List.of(match), service.list(filter));
        verify(service.userCollection, never()).find(any(Query.class));
    }

    private TraceService service() {
        var service = new TraceService();
        service.traceCollection = traceCollection();
        service.spanCollection = spanCollection();
        service.userCollection = userCollection();
        return service;
    }
}
