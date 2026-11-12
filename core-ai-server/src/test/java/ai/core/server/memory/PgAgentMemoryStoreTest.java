package ai.core.server.memory;

import ai.core.server.memory.experiment.MemoryLayer;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgAgentMemoryStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-memory-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgAgentMemoryRow.class);
        database.view(PgAgentMemoryAgentRow.class);
        database.view(PgAgentMemoryCursorRow.class);
        createTables();
        return database;
    }

    private static void createTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS app");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_memories ("
                + "id text PRIMARY KEY, agent_id text NOT NULL, type text, layer text NOT NULL, content text, "
                + "created_at timestamptz NOT NULL, updated_at timestamptz, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_memory_extraction_cursors ("
                + "id text PRIMARY KEY, agent_id text NOT NULL UNIQUE, data jsonb NOT NULL, updated_at timestamptz NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize agent memory test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        DATABASE.execute("DELETE FROM app.agent_memories");
        DATABASE.execute("DELETE FROM app.agent_memory_extraction_cursors");
    }

    @Test
    void memoryLayersSearchDeleteAndCursor() {
        var store = new PgAgentMemoryStore();
        store.database = DATABASE;
        var knowledge = memory("memory-1", MemoryLayer.KNOWLEDGE, "spa preference");
        var method = memory("memory-2", MemoryLayer.METHODS, "run the report");
        store.insert(knowledge);
        store.insert(method);

        assertEquals(2, store.findByAgentId("agent-1").size());
        assertEquals(1, store.findByAgentIdAndLayer("agent-1", "knowledge").size());
        assertEquals(1, store.findAgentIdsWithMemories().size());
        assertEquals(1, store.search("agent-1", "report", "methods", 10).size());

        assertTrue(store.delete("memory-1"));
        assertFalse(store.delete("memory-1"));

        var cursor = new AgentMemoryExtractionCursor();
        cursor.id = "cursor-1";
        cursor.agentId = "agent-1";
        cursor.updatedAt = ZonedDateTime.now();
        store.saveCursor(cursor);
        var loaded = store.getCursor("agent-1");
        assertEquals("cursor-1", loaded.id);
        loaded.updatedAt = ZonedDateTime.now();
        store.saveCursor(loaded);
        assertEquals("cursor-1", store.getCursor("agent-1").id);
    }

    private AgentMemory memory(String id, MemoryLayer layer, String content) {
        var memory = new AgentMemory();
        memory.id = id;
        memory.agentId = "agent-1";
        memory.type = "DOMAIN_KNOWLEDGE";
        memory.layer = layer;
        memory.content = content;
        memory.createdAt = ZonedDateTime.now();
        memory.updatedAt = memory.createdAt;
        return memory;
    }
}
