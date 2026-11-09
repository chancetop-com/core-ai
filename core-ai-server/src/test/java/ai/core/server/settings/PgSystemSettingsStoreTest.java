package ai.core.server.settings;

import ai.core.server.domain.SystemSettings;
import ai.core.server.pg.PgDocumentRow;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL round-trip for the JSONB-backed settings store. Enabled by CORE_AI_PG_TEST_URL so the
 * default build stays independent of a database.
 *
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgSystemSettingsStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("system-settings-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.system_settings ("
                    + "id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize system settings test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgSystemSettingsStore store;

    @BeforeEach
    void setUp() {
        store = new PgSystemSettingsStore();
        store.database = DATABASE;
    }

    @Test
    void roundTripsJsonDocument() {
        var id = "test-" + UUID.randomUUID();
        var entity = new SystemSettings();
        entity.id = id;
        entity.llmModel = "model-a";
        entity.sandboxSnapshotEnabled = Boolean.TRUE;
        entity.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        entity.updatedAt = ZonedDateTime.parse("2026-11-06T01:00:00Z");
        try {
            store.insert(entity);

            var loaded = store.get(id).orElseThrow();
            assertEquals("model-a", loaded.llmModel);
            assertEquals(Boolean.TRUE, loaded.sandboxSnapshotEnabled);
            assertEquals(entity.createdAt.toInstant(), loaded.createdAt.toInstant());

            loaded.llmModel = "model-b";
            store.replace(loaded);
            assertEquals("model-b", store.get(id).orElseThrow().llmModel);
        } finally {
            store.delete(id);
        }
        assertTrue(store.get(id).isEmpty());
    }
}
