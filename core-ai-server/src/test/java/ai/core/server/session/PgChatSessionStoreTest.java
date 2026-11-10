package ai.core.server.session;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.ToolRef;
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
class PgChatSessionStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("chat-session-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgChatSessionRow.class);
        database.view(PgChatSessionCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.chat_sessions ("
                    + "id text PRIMARY KEY, user_id text NOT NULL, agent_id text, source text, comparison_id text, "
                    + "comparison_pair_id text, comparison_variant text, title text, message_count bigint, "
                    + "created_at timestamptz NOT NULL, last_message_at timestamptz, notify_on_complete boolean, "
                    + "deleted_at timestamptz, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize chat session test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgChatSessionStore store;

    @BeforeEach
    void setUp() {
        store = new PgChatSessionStore();
        store.database = DATABASE;
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("TRUNCATE app.chat_sessions");
        } catch (Exception e) {
            throw new IllegalStateException("failed to reset chat session test table", e);
        }
    }

    @Test
    void roundTripsAndMutatesSession() {
        var session = session("chat", "agent-1");
        assertTrue(store.insertIfAbsent(session));
        assertFalse(store.insertIfAbsent(session));
        assertIdentityAndCounters(session);
        assertLoadedResourcesArtifactsAndFlags(session);
    }

    private void assertIdentityAndCounters(ChatSession session) {
        var loaded = store.get(session.id).orElseThrow();
        assertEquals(session.userId, loaded.userId);
        assertEquals("agent-1", loaded.agentId);
        assertNotNull(loaded.createdAt);

        var now = ZonedDateTime.now();
        store.recordUserMessage(session.id, "first title", now);
        loaded = store.get(session.id).orElseThrow();
        assertEquals("first title", loaded.title);
        assertEquals(1L, loaded.messageCount);
        assertEquals(now.toInstant(), loaded.lastMessageAt.toInstant());

        store.recordUserMessage(session.id, "second title", now.plusSeconds(1));
        loaded = store.get(session.id).orElseThrow();
        assertEquals("first title", loaded.title);
        assertEquals(2L, loaded.messageCount);

        store.recordAgentMessage(session.id, now.plusSeconds(2));
        assertEquals(3L, store.get(session.id).orElseThrow().messageCount);
    }

    private void assertLoadedResourcesArtifactsAndFlags(ChatSession session) {
        var tool = ToolRef.of("builtin:self-harness:list_agents", ToolSourceType.BUILTIN, "self-harness");
        store.addLoadedTools(session.id, List.of(tool));
        store.addLoadedTools(session.id, List.of(tool));
        store.addLoadedSkillIds(session.id, List.of("skill-1", "skill-2"));
        store.addLoadedSubAgentIds(session.id, List.of("agent-2"));
        store.removeLoadedSkillIds(session.id, List.of("skill-1"));
        var loaded = store.get(session.id).orElseThrow();
        assertEquals(1, loaded.loadedTools.size());
        assertEquals(List.of("skill-2"), loaded.loadedSkillIds);
        assertEquals(List.of("agent-2"), loaded.loadedSubAgentIds);

        var artifact = new AgentRunArtifact();
        artifact.fileId = "file-1";
        artifact.fileName = "result.png";
        artifact.createdAt = ZonedDateTime.now();
        store.appendArtifact(session.id, artifact);
        loaded = store.get(session.id).orElseThrow();
        assertEquals(1, loaded.artifacts.size());
        assertEquals("file-1", loaded.artifacts.getFirst().fileId);

        store.recordComparison(session.id, "comparison-1", "a", "pair-1");
        loaded = store.get(session.id).orElseThrow();
        assertEquals("comparison-1", loaded.comparisonId);
        assertEquals("a", loaded.comparisonVariant);
        assertEquals("pair-1", loaded.comparisonPairId);

        store.updateTitle(session.id, "renamed");
        store.updateNotifyOnComplete(session.id, true);
        loaded = store.get(session.id).orElseThrow();
        assertEquals("renamed", loaded.title);
        assertTrue(loaded.notifyOnComplete);
    }

    @Test
    void filtersByUserAgentAndPair() {
        var a = insert(null, "user-1", "agent-1", "a", "pair-1");
        insert("api", "user-1", "agent-1", "b", "pair-1");
        insert("chat", "user-2", "agent-2", null, null);
        var deleted = insert("chat", "user-1", "agent-1", null, null);
        store.softDelete(deleted.id, ZonedDateTime.now());

        var userQuery = new ChatSessionQuery();
        userQuery.userId = "user-1";
        userQuery.excludeComparisonVariantB = true;
        assertEquals(1, store.count(userQuery));

        userQuery.sources = List.of("chat");
        assertEquals(1, store.find(userQuery).size());
        assertEquals(a.id, store.find(userQuery).getFirst().id);

        var agentQuery = new ChatSessionQuery();
        agentQuery.agentIds = List.of("agent-1");
        assertEquals(2, store.count(agentQuery));
        agentQuery.agentIds = List.of();
        assertEquals(0, store.count(agentQuery));

        var pairQuery = new ChatSessionQuery();
        pairQuery.comparisonPairId = "pair-1";
        assertEquals(2, store.count(pairQuery));
    }

    @Test
    void filtersByArtifactsAndDeletion() {
        var c = insert("chat", "user-2", "agent-2", null, null);
        var artifact = new AgentRunArtifact();
        artifact.fileId = "file-2";
        c.artifacts = List.of(artifact);
        store.upsert(c);

        var deleted = insert("chat", "user-1", "agent-1", null, null);
        store.softDelete(deleted.id, ZonedDateTime.now());

        var artifactQuery = new ChatSessionQuery();
        artifactQuery.hasArtifacts = Boolean.TRUE;
        assertEquals(1, store.count(artifactQuery));
        artifactQuery.artifactFileIds = List.of("file-2");
        assertEquals(1, store.count(artifactQuery));

        var includeDeleted = new ChatSessionQuery();
        includeDeleted.userId = "user-1";
        includeDeleted.includeDeleted = true;
        assertEquals(1, store.count(includeDeleted));
        includeDeleted.includeDeleted = false;
        assertEquals(0, store.count(includeDeleted));
    }

    private ChatSession insert(String source, String userId, String agentId, String variant, String pairId) {
        var session = session(source, agentId);
        session.userId = userId;
        session.comparisonVariant = variant;
        session.comparisonPairId = pairId;
        store.insertIfAbsent(session);
        return session;
    }

    @Test
    void keepsMongoNullOrdering() {
        var noMessages = insert("chat", "user-1", "agent-1", null, null);
        var withMessages = insert("chat", "user-1", "agent-1", null, null);
        withMessages.lastMessageAt = ZonedDateTime.now();
        store.upsert(withMessages);

        var descending = new ChatSessionQuery();
        descending.userId = "user-1";
        assertEquals(noMessages.id, store.find(descending).getLast().id);

        var ascending = new ChatSessionQuery();
        ascending.userId = "user-1";
        ascending.ascending = true;
        assertEquals(noMessages.id, store.find(ascending).getFirst().id);
    }

    @Test
    void upsertCopiesAndReplaces() {
        var session = session("chat", "agent-1");
        store.upsert(session);
        assertEquals(1, store.count(new ChatSessionQuery()));

        session.title = "from mongo";
        session.messageCount = 42L;
        store.upsert(session);
        var loaded = store.get(session.id).orElseThrow();
        assertEquals("from mongo", loaded.title);
        assertEquals(42L, loaded.messageCount);
    }

    private ChatSession session(String source, String agentId) {
        var session = new ChatSession();
        session.id = UUID.randomUUID().toString();
        session.userId = "user-" + UUID.randomUUID();
        session.agentId = agentId;
        session.source = source;
        session.messageCount = 0L;
        session.createdAt = ZonedDateTime.now();
        return session;
    }
}
