package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentVersion;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgAgentVersionStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-version-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgAgentVersionRow.class);
        database.view(PgAgentVersionCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_versions ("
                + "id text PRIMARY KEY, agent_id text NOT NULL, agent_name text, created_by text, "
                + "created_at timestamptz, updated_at timestamptz, data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize agent version test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgAgentVersionStore store;

    @BeforeEach
    void setUp() {
        store = new PgAgentVersionStore();
        store.database = DATABASE;
        clearTable();
    }

    private void clearTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM app.agent_versions");
        } catch (Exception e) {
            throw new IllegalStateException("failed to clear agent version test data", e);
        }
    }

    @Test
    void insertListCountReplaceAndDelete() {
        var older = version("version-older", "agent-1", ZonedDateTime.now().minusMinutes(1));
        var newer = version("version-newer", "agent-1", ZonedDateTime.now());
        store.insert(older);
        store.insert(newer);

        assertTrue(store.get("version-older").isPresent());
        assertEquals(List.of("version-newer", "version-older"),
            store.listByAgentId("agent-1", 10).stream().map(v -> v.id).toList());
        assertEquals(2, store.countByAgentId("agent-1"));

        older.label = "updated";
        store.replace(older);
        assertEquals("updated", store.get("version-older").orElseThrow().label);

        store.delete("version-older");
        assertTrue(store.get("version-older").isEmpty());
    }

    @Test
    void insertIfAbsentDoesNotOverwrite() {
        var version = version("version-1", "agent-1", ZonedDateTime.now());
        assertTrue(store.insertIfAbsent(version));
        version.label = "changed";
        assertFalse(store.insertIfAbsent(version));
        assertEquals("version-1", store.get("version-1").orElseThrow().label);
    }

    private AgentVersion version(String id, String agentId, ZonedDateTime createdAt) {
        var version = new AgentVersion();
        version.id = id;
        version.agentId = agentId;
        version.agentName = agentId;
        version.label = id;
        version.createdAt = createdAt;
        version.updatedAt = createdAt;
        return version;
    }
}
