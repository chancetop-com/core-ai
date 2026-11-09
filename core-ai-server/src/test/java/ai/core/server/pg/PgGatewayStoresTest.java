package ai.core.server.pg;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.gateway.PgGatewayModelStore;
import ai.core.server.gateway.PgGatewayProviderStore;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
class PgGatewayStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("gateway-store-test");
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.gateway_provider "
                    + "(id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE TABLE IF NOT EXISTS app.gateway_model "
                    + "(id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize gateway test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private static GatewayModelConfig model(String modelId, String providerId) {
        var model = new GatewayModelConfig();
        model.id = UUID.randomUUID().toString();
        model.modelId = modelId;
        model.providerId = providerId;
        model.upstreamModel = "upstream";
        model.enabled = Boolean.TRUE;
        model.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        model.updatedAt = model.createdAt;
        return model;
    }

    private static <T> T withDatabase(T store) {
        try {
            var field = store.getClass().getDeclaredField("database");
            field.setAccessible(true);
            field.set(store, DATABASE);
            return store;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to inject test database", e);
        }
    }

    @Test
    void providerAndModelQueries() {
        var providerStore = withDatabase(new PgGatewayProviderStore());
        var modelStore = withDatabase(new PgGatewayModelStore());

        var provider = new GatewayProviderConfig();
        provider.id = UUID.randomUUID().toString();
        provider.name = "Provider";
        provider.type = "litellm";
        provider.enabled = Boolean.TRUE;
        provider.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        provider.updatedAt = provider.createdAt;

        var model = model("model-" + provider.id, provider.id);
        model.isDefault = Boolean.TRUE;
        model.messagesFallback = Boolean.TRUE;
        model.endpointTypes = List.of("chat.completions");
        try {
            providerStore.insert(provider);
            modelStore.insert(model);

            assertEquals(1, providerStore.listAll().stream().filter(item -> provider.id.equals(item.id)).count());
            assertEquals(1, modelStore.listByProviderId(provider.id).size());
            assertEquals(1, modelStore.listByModelId(model.modelId).size());
            assertTrue(modelStore.existsEnabledModel(model.modelId));
            assertTrue(modelStore.existsEnabledModelWithEndpoint(model.modelId, "chat.completions"));

            modelStore.clearDefault(provider.id);
            assertNotEquals(Boolean.TRUE, modelStore.get(model.id).orElseThrow().isDefault);
            modelStore.clearMessagesFallbackExcept("another-id");
            assertNotEquals(Boolean.TRUE, modelStore.get(model.id).orElseThrow().messagesFallback);
        } finally {
            modelStore.delete(model.id);
            providerStore.delete(provider.id);
        }
    }

}
