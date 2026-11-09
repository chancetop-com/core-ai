package ai.core.server.apiuser;



import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import ai.core.server.domain.ApiKey;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "SACM_STATIC_ARRAY_CREATED_IN_METHOD", "RFI_SET_ACCESSIBLE"})
class PgApiKeyStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("api-key-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgApiKeyRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.api_keys ("
                    + "id text PRIMARY KEY, key_hash text NOT NULL, key_prefix text, user_id text NOT NULL, "
                    + "scope text, metadata jsonb, status text, expires_at timestamptz, created_at timestamptz, "
                    + "last_used_at timestamptz, revoked_at timestamptz)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS api_keys_key_hash_idx ON app.api_keys (key_hash)");
            statement.execute("CREATE INDEX IF NOT EXISTS api_keys_user_created_idx ON app.api_keys (user_id, created_at DESC)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize api key test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgApiKeyStore store;

    @BeforeEach
    void setUp() {
        store = new PgApiKeyStore();
        store.database = DATABASE;
    }

    @Test
    void roundTripsKeyAndFindsByHash() {
        var id = "k_" + UUID.randomUUID();
        var entity = new ApiKey();
        entity.id = id;
        entity.keyHash = "hash-" + id;
        entity.keyPrefix = "ctk_test";
        entity.userId = "user-1";
        entity.scope = ApiUserKeyService.SCOPE_CALL;
        entity.status = "active";
        entity.metadata = Map.of("env", "test");
        entity.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        entity.expiresAt = ZonedDateTime.parse("2026-11-06T01:00:00Z");
        try {
            store.insert(entity);

            var loaded = store.get(id).orElseThrow();
            assertEquals("user-1", loaded.userId);
            assertEquals("test", loaded.metadata.get("env"));
            assertEquals(entity.expiresAt.toInstant(), loaded.expiresAt.toInstant());

            assertTrue(store.findByKeyHash(entity.keyHash).isPresent());
            assertEquals(1, store.listByUser("user-1").size());

            store.revoke(id, ZonedDateTime.parse("2026-11-06T02:00:00Z"));
            assertEquals("revoked", store.get(id).orElseThrow().status);
        } finally {
            DATABASE.execute("DELETE FROM app.api_keys WHERE id = ?", id);
        }
    }
}
