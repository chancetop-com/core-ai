package ai.core.server.hub;

import ai.core.server.domain.HubCall;
import ai.core.server.domain.User;
import com.mongodb.MongoClientSettings;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.mongo.impl.ZonedDateTimeCodec;
import core.framework.web.exception.BadRequestException;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class HubCallQueryServiceTest {
    private static final CodecRegistry CODEC_REGISTRY = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs(new ZonedDateTimeCodec()),
            MongoClientSettings.getDefaultCodecRegistry());

    private HubCallQueryService service;
    private MongoCollection<HubCall> callCollection;
    private MongoCollection<User> userCollection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new HubCallQueryService();
        callCollection = (MongoCollection<HubCall>) mock(MongoCollection.class);
        userCollection = (MongoCollection<User>) mock(MongoCollection.class);
        service.callCollection = callCollection;
        service.userCollection = userCollection;
    }

    @Test
    void listAppliesFiltersAndPaging() {
        var filter = new HubCallListFilter();
        filter.kind = "MCP_TOOL";
        filter.source = "CLI";
        filter.userId = "u1";
        filter.state = "failed";
        filter.startFrom = ZonedDateTime.now().minusDays(7);
        filter.offset = 20;
        filter.limit = 50;
        when(callCollection.count(any(Bson.class))).thenReturn(3L);
        when(callCollection.find(any(Query.class))).thenReturn(List.of());

        var response = service.list(filter);

        assertEquals(3L, response.total.longValue());
        assertTrue(response.calls.isEmpty());
        var query = capturedQuery();
        var json = toJson(query.filter);
        assertTrue(json.contains("\"kind\": \"mcp_tool\""), json);
        assertTrue(json.contains("\"source\": \"cli\""), json);
        assertTrue(json.contains("\"user_id\": \"u1\""), json);
        assertTrue(json.contains("\"success\": false"), json);
        assertTrue(json.contains("\"created_at\""), json);
        assertEquals(20, query.skip);
        assertEquals(50, query.limit);
        assertEquals("{\"created_at\": -1}", toJson(query.sort));
    }

    @Test
    void listWithoutKindOrStateKeepsTheWindowOnly() {
        when(callCollection.count(any(Bson.class))).thenReturn(0L);
        when(callCollection.find(any(Query.class))).thenReturn(List.of());

        service.list(filter());

        var json = toJson(capturedQuery().filter);
        assertFalse(json.contains("kind"), json);
        assertFalse(json.contains("success"), json);
    }

    @Test
    void listFiltersIncompleteCallsByNullSuccess() {
        var filter = filter();
        filter.state = "unknown";
        when(callCollection.count(any(Bson.class))).thenReturn(0L);
        when(callCollection.find(any(Query.class))).thenReturn(List.of());

        service.list(filter);

        var json = toJson(capturedQuery().filter);
        assertTrue(json.contains("\"success\": null"), json);
    }

    @Test
    void listRejectsUnknownKind() {
        var filter = filter();
        filter.kind = "workflow";

        assertThrows(BadRequestException.class, () -> service.list(filter));
    }

    @Test
    void listRejectsUnknownState() {
        var filter = filter();
        filter.state = "pending";

        assertThrows(BadRequestException.class, () -> service.list(filter));
    }

    @Test
    void listMapsRowsAndResolvesAccounts() {
        when(callCollection.count(any(Bson.class))).thenReturn(3L);
        when(callCollection.find(any(Query.class))).thenReturn(List.of(call("c1", "u1"), call("c2", "u1"), call("c3", "ghost")));
        var user = new User();
        user.id = "u1";
        user.name = "Alice";
        user.email = "alice@example.com";
        when(userCollection.get("u1")).thenReturn(Optional.of(user));
        when(userCollection.get("ghost")).thenReturn(Optional.empty());

        var response = service.list(filter());

        assertEquals(3, response.calls.size());
        var view = response.calls.getFirst();
        assertEquals("c1", view.id);
        assertEquals("mcp_tool", view.kind);
        assertEquals("cli", view.source);
        assertEquals("demo-server/search", view.target);
        assertEquals("demo-server", view.group);
        assertEquals("search", view.name);
        assertEquals("u1", view.userId);
        assertEquals("user", view.userType);
        assertEquals("Alice", view.userName);
        assertEquals("alice@example.com", view.userEmail);
        assertEquals(Boolean.TRUE, view.success);
        assertEquals(Boolean.FALSE, view.isError);
        assertEquals(200, view.statusCode.intValue());
        assertEquals(123L, view.durationMs.longValue());
        assertEquals(42, view.outputBytes.intValue());
        assertEquals("args-preview", view.argsPreview);
        assertEquals("args-hash", view.argsHash);
        assertEquals("task-1", view.taskId);
        assertEquals("context-1", view.contextId);
        assertEquals(10L, view.inputTokens.longValue());
        assertEquals(20L, view.outputTokens.longValue());
        assertNotNull(view.createdAt);
        assertNull(response.calls.get(2).userName);
        assertNull(response.calls.get(2).userEmail);
        verify(userCollection, times(1)).get("u1");
    }

    private HubCallListFilter filter() {
        var filter = new HubCallListFilter();
        filter.startFrom = ZonedDateTime.now().minusDays(7);
        filter.limit = 20;
        return filter;
    }

    private HubCall call(String id, String userId) {
        var call = new HubCall();
        call.id = id;
        call.kind = "mcp_tool";
        call.userId = userId;
        call.userType = "user";
        call.source = "cli";
        call.target = "demo-server/search";
        call.group = "demo-server";
        call.name = "search";
        call.argsHash = "args-hash";
        call.argsPreview = "args-preview";
        call.success = Boolean.TRUE;
        call.isError = Boolean.FALSE;
        call.statusCode = 200;
        call.durationMs = 123L;
        call.outputBytes = 42;
        call.taskId = "task-1";
        call.contextId = "context-1";
        call.inputTokens = 10L;
        call.outputTokens = 20L;
        call.createdAt = ZonedDateTime.now();
        return call;
    }

    private Query capturedQuery() {
        var captor = ArgumentCaptor.forClass(Query.class);
        verify(callCollection).find(captor.capture());
        return captor.getValue();
    }

    private String toJson(Bson bson) {
        return bson.toBsonDocument(BsonDocument.class, CODEC_REGISTRY).toJson();
    }
}
