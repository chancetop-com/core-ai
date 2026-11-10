package ai.core.server.session;

import ai.core.server.domain.ChatMessage;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgChatMessageStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("chat-message-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgChatMessageRow.class);
        database.view(PgChatMessageCountRow.class);
        database.view(PgChatMessageSeqRow.class);
        createTable();
        return database;
    }

    private static void createTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE TABLE IF NOT EXISTS app.chat_messages ("
                    + "id text PRIMARY KEY, session_id text NOT NULL, seq bigint NOT NULL, role text, trace_id text, "
                    + "content text, created_at timestamptz NOT NULL, data jsonb NOT NULL, "
                    + "updated_at timestamptz NOT NULL DEFAULT now(), "
                    + "CONSTRAINT uq_chat_messages_session_seq UNIQUE (session_id, seq))");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize chat message test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgChatMessageStore store;

    @BeforeEach
    void setUp() {
        store = new PgChatMessageStore();
        store.database = DATABASE;
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE app.chat_messages");
        } catch (Exception e) {
            throw new IllegalStateException("failed to reset chat message test table", e);
        }
    }

    @Test
    void roundTripsContentAndNestedPayload() {
        var message = message("s-1", 1L, "user", "hello", ZonedDateTime.now());
        message.thinking = "reasoning";
        var tool = new ChatMessage.ToolCallRecord();
        tool.callId = "call-1";
        tool.name = "run_bash_command";
        tool.arguments = "{}";
        tool.result = "ok";
        tool.status = "done";
        message.tools = List.of(tool);
        var event = new ChatMessage.EventRecord();
        event.name = "menu_table";
        event.data = "{\"rows\":[]}";
        message.events = List.of(event);

        assertTrue(store.insertIfAbsent(message));
        assertFalse(store.insertIfAbsent(message));

        var loaded = store.get(message.id).orElseThrow();
        assertEquals("hello", loaded.content);
        assertEquals("reasoning", loaded.thinking);
        assertEquals("run_bash_command", loaded.tools.getFirst().name);
        assertEquals("menu_table", loaded.events.getFirst().name);
        assertEquals(1L, store.latestSeq("s-1"));
    }

    @Test
    void stripsNulCharactersThatPostgresRejects() {
        var message = message("s-1", 1L, "agent", "before\u0000after", ZonedDateTime.now());
        var tool = new ChatMessage.ToolCallRecord();
        tool.callId = "call-1";
        tool.name = "run_bash_command";
        tool.result = "image\u0000bytes";
        message.tools = List.of(tool);

        assertTrue(store.insertIfAbsent(message));

        var loaded = store.get(message.id).orElseThrow();
        assertEquals("beforeafter", loaded.content);
        assertEquals("imagebytes", loaded.tools.getFirst().result);

        var literal = message("s-1", 2L, "user", "path\\u0000literal", ZonedDateTime.now());
        assertTrue(store.insertIfAbsent(literal));
        assertEquals("path\\u0000literal", store.get(literal.id).orElseThrow().content);
    }

    @Test
    void returnsHistoryInSequenceOrderAndCountsByRole() {
        store.insertIfAbsent(message("s-1", 2L, "agent", "second", ZonedDateTime.now()));
        store.insertIfAbsent(message("s-1", 1L, "user", "first", ZonedDateTime.now()));

        var history = new ChatMessageQuery();
        history.sessionId = "s-1";
        assertEquals(List.of("first", "second"), store.find(history).stream().map(message -> message.content).toList());
        assertEquals(2L, store.latestSeq("s-1"));

        var userTurns = new ChatMessageQuery();
        userTurns.sessionId = "s-1";
        userTurns.role = "user";
        assertEquals(1L, store.count(userTurns));
    }

    @Test
    void searchesByTermsAndMatchesAnyTraceOrSessionWindow() {
        var now = ZonedDateTime.now();
        store.insertIfAbsent(message("s-1", 1L, "user", "Terraform plan", now.minusMinutes(5)));
        store.insertIfAbsent(message("s-1", 2L, "agent", "Applied the stack", now.minusMinutes(4)));
        store.insertIfAbsent(message("s-2", 1L, "user", "unrelated", now.minusMinutes(3)));
        var traced = message("s-3", 1L, "user", "trace hit", now.minusMinutes(2));
        traced.traceId = "t-1";
        store.insertIfAbsent(traced);

        var search = new ChatMessageQuery();
        search.sessionIds = List.of("s-1", "s-2");
        search.contentTerms = List.of("terraform", "stack");
        search.sortField = "created_at";
        search.ascending = false;
        assertEquals(2, store.find(search).size());

        var scope = new ChatMessageQuery();
        scope.anyScope = true;
        scope.traceIds = List.of("t-1");
        var window = new ChatMessageQuery.SessionWindow();
        window.sessionId = "s-2";
        window.from = now.minusMinutes(10);
        window.to = now;
        scope.sessionWindows = List.of(window);
        scope.sortField = "created_at";
        assertEquals(2, store.find(scope).size());

        assertNotNull(store.get(traced.id).orElse(null));
    }

    private ChatMessage message(String sessionId, long seq, String role, String content, ZonedDateTime createdAt) {
        var message = new ChatMessage();
        message.id = UUID.randomUUID().toString();
        message.sessionId = sessionId;
        message.seq = seq;
        message.role = role;
        message.content = content;
        message.createdAt = createdAt;
        return message;
    }
}
