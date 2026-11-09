package ai.core.server.user;

import ai.core.server.domain.User;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgUserStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("user-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgUserRow.class);
        database.view(PgUserIdRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.users ("
                    + "id text PRIMARY KEY, name text, email text, api_key text, role text, status text, "
                    + "user_type text, owner_id text, external_id text, created_at timestamptz, last_login_at timestamptz, "
                    + "data jsonb NOT NULL)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_email_idx ON app.users (email) WHERE email IS NOT NULL");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS users_api_key_idx ON app.users (api_key) WHERE api_key IS NOT NULL");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize user test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgUserStore store;

    @BeforeEach
    void setUp() {
        store = new PgUserStore();
        store.database = DATABASE;
    }

    @Test
    void roundTripsUserAndQueries() {
        var id = "user-" + UUID.randomUUID();
        var user = new User();
        user.id = id;
        user.name = "User One";
        user.email = id + "@example.com";
        user.apiKey = "key-" + id;
        user.role = "user";
        user.status = "active";
        user.userType = "internal";
        user.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        try {
            store.insert(user);

            var loaded = store.get(id).orElseThrow();
            assertEquals("User One", loaded.name);
            assertEquals(1, store.listByIds(List.of(id)).size());
            assertEquals(1, store.listByUserType("internal").stream().filter(u -> id.equals(u.id)).count());
            assertTrue(store.findByApiKey(user.apiKey).isPresent());

            assertTrue(store.update(id, entity -> entity.name = "Renamed"));
            assertEquals("Renamed", store.get(id).orElseThrow().name);

            var quotaUser = store.get(id).orElseThrow();
            quotaUser.quotaInputTokens = 100L;
            store.replace(quotaUser);
            store.incrementQuotaUsage(id, 10L, 0L);
            assertEquals(10L, store.get(id).orElseThrow().quotaConsumedInputTokens);
            store.resetQuota(id, ZonedDateTime.parse("2026-11-07T00:00:00Z"));
            assertEquals(0L, store.get(id).orElseThrow().quotaConsumedInputTokens);
        } finally {
            store.delete(id);
        }
        assertTrue(store.get(id).isEmpty());
    }
}
