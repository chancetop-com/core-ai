package ai.core.server.pg;



import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import ai.core.server.channel.ChannelConfigView;
import ai.core.server.channel.PgChannelConfigStore;
import ai.core.server.channel.openclaw.OcgConfigView;
import ai.core.server.channel.openclaw.PgOcgConfigStore;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "SACM_STATIC_ARRAY_CREATED_IN_METHOD", "RFI_SET_ACCESSIBLE"})
class PgChannelStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("channel-store-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
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
            for (var table : new String[]{"channels", "ocg_configs"}) {
                statement.execute("CREATE TABLE IF NOT EXISTS app." + table
                    + " (id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize channel store test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static <T> T withDatabase(T store) {
        try {
            var field = store.getClass().getDeclaredField("database");
            field.setAccessible(true);
            field.set(store, DATABASE);
            return store;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void channelStoreRoundTrip() {
        var store = withDatabase(new PgChannelConfigStore());
        var channel = new ChannelConfigView();
        channel.channelId = "channel-" + UUID.randomUUID();
        channel.channelType = "telegram";
        channel.enabled = Boolean.TRUE;
        channel.requireAuth = Boolean.FALSE;
        channel.config = Map.of("token", "secret");
        try {
            store.store(channel);
            assertEquals("telegram", store.load(channel.channelId).channelType);
            assertTrue(store.all().containsKey(channel.channelId));

            channel.channelType = "slack";
            store.store(channel);
            assertEquals("slack", store.load(channel.channelId).channelType);
        } finally {
            store.remove(channel.channelId);
        }
        assertNull(store.load(channel.channelId));
    }

    @Test
    void ocgStoreQueriesAndClearsSandbox() {
        var store = withDatabase(new PgOcgConfigStore());
        var config = new OcgConfigView();
        config.id = "ocg-" + UUID.randomUUID();
        config.channelId = "channel-" + UUID.randomUUID();
        config.configJson = "{}";
        config.enabled = Boolean.TRUE;
        config.sandboxId = "sandbox-1";
        config.sandboxIp = "127.0.0.1";
        try {
            store.store(config);
            assertEquals(config.id, store.loadByChannelId(config.channelId).id);
            assertTrue(store.all().containsKey(config.id));
            assertEquals(1, store.allWithSandbox().stream().filter(item -> item.id.equals(config.id)).count());

            store.clearSandbox(config.id);
            var updated = store.load(config.id);
            assertNull(updated.sandboxId);
            assertNull(updated.sandboxIp);
            assertTrue(store.allWithSandbox().stream().noneMatch(item -> item.id.equals(config.id)));
        } finally {
            store.remove(config.id);
        }
        assertNull(store.load(config.id));
    }
}
