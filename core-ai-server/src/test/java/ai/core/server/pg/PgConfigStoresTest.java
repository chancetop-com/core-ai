package ai.core.server.pg;

import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import ai.core.server.apimcp.serviceapi.service.PgServiceApiStore;
import ai.core.server.domain.SystemPrompt;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.systemprompt.PgSystemPromptStore;
import ai.core.server.tool.PgToolRegistryStore;
import ai.core.server.trace.domain.PromptStatus;
import ai.core.server.trace.domain.PromptTemplate;
import ai.core.server.trace.service.PgPromptTemplateStore;
import ai.core.server.trigger.PgTriggerStore;
import ai.core.server.trigger.domain.Trigger;
import ai.core.server.trigger.domain.TriggerType;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
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
class PgConfigStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("config-store-test");
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
            for (var table : new String[]{"system_prompts", "prompt_templates", "triggers", "service_api", "tool_registry"}) {
                statement.execute("CREATE TABLE IF NOT EXISTS app." + table
                        + " (id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize config store test schema", e);
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
            throw new IllegalStateException("failed to inject test database", e);
        }
    }

    @Test
    void systemPromptReadsLatestByNameAndVersions() {
        var store = withDatabase(new PgSystemPromptStore());
        var promptId = UUID.randomUUID().toString();
        var first = prompt(promptId, "support", 1);
        var second = prompt(promptId, "support", 2);
        try {
            store.insert(first);
            store.insert(second);

            assertEquals(2, store.latestByPromptId(promptId).version);
            assertEquals(2, store.latestByName("support").version);
            assertEquals(2, store.versions(promptId).size());
            assertEquals(1, store.version(promptId, 1).version);
        } finally {
            store.deleteByPromptId(promptId);
        }
        assertTrue(store.versions(promptId).isEmpty());
    }

    @Test
    void promptTemplateRoundTrip() {
        var store = withDatabase(new PgPromptTemplateStore());
        var template = new PromptTemplate();
        template.id = UUID.randomUUID().toString();
        template.name = "template";
        template.template = "hello {{name}}";
        template.status = PromptStatus.PUBLISHED;
        template.version = 1;
        template.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        template.updatedAt = template.createdAt;
        try {
            store.insert(template);
            assertEquals("template", store.get(template.id).orElseThrow().name);
            assertEquals(1, store.list(0, 10).size());
        } finally {
            store.delete(template.id);
        }
    }

    @Test
    void toolRegistryQueries() {
        var store = withDatabase(new PgToolRegistryStore());
        var entry = new ToolRegistryEntry();
        entry.id = UUID.randomUUID().toString();
        entry.name = "mcp-server";
        entry.type = ToolType.MCP;
        entry.enabled = Boolean.FALSE;
        entry.config = Map.of("url", "https://example.com/mcp");
        entry.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        try {
            store.insert(entry);
            assertEquals(1, store.listByType(ToolType.MCP).size());
            assertEquals(1, store.listEnabledOrMcp().size());
            assertTrue(store.findMcpServerByName("mcp-server").isPresent());
        } finally {
            store.delete(entry.id);
        }
    }

    @Test
    void triggerAndServiceApiRoundTrip() {
        var triggerStore = withDatabase(new PgTriggerStore());
        var trigger = new Trigger();
        trigger.id = UUID.randomUUID().toString();
        trigger.name = "trigger";
        trigger.type = TriggerType.WEBHOOK;
        trigger.enabled = Boolean.TRUE;
        trigger.actionType = "agent";
        trigger.config = Map.of("verifier_type", "bearer");
        trigger.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        trigger.updatedAt = trigger.createdAt;
        triggerStore.insert(trigger);
        assertEquals(1, triggerStore.list("WEBHOOK").size());

        var apiStore = withDatabase(new PgServiceApiStore());
        var api = new ServiceApi();
        api.id = UUID.randomUUID().toString();
        api.name = "orders";
        api.version = "1";
        api.enabled = Boolean.TRUE;
        api.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        api.updatedAt = api.createdAt;
        try {
            apiStore.insert(api);
            assertEquals(api.id, apiStore.getByName("orders").orElseThrow().id);
            assertTrue(apiStore.listAll().stream().anyMatch(item -> api.id.equals(item.id)));
        } finally {
            apiStore.delete(api.id);
            triggerStore.delete(trigger.id);
        }
    }

    private SystemPrompt prompt(String promptId, String name, int version) {
        var prompt = new SystemPrompt();
        prompt.id = UUID.randomUUID().toString();
        prompt.promptId = promptId;
        prompt.name = name;
        prompt.content = "content";
        prompt.version = version;
        prompt.userId = "user-1";
        prompt.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z").plusMinutes(version);
        return prompt;
    }
}
