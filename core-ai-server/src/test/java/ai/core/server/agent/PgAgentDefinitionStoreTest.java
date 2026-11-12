package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
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
class PgAgentDefinitionStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-definition-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgAgentDefinitionRow.class);
        database.view(PgAgentCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.agents ("
                + "id text PRIMARY KEY, user_id text NOT NULL, name text NOT NULL, name_key text, "
                + "type text NOT NULL, status text NOT NULL, system_default boolean, forked_from text, "
                + "has_published_config boolean NOT NULL DEFAULT false, created_at timestamptz NOT NULL, "
                + "updated_at timestamptz NOT NULL, data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize agent definition test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgAgentDefinitionStore store;

    @BeforeEach
    void setUp() {
        store = new PgAgentDefinitionStore();
        store.database = DATABASE;
        clearTable();
    }

    private void clearTable() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM app.agents");
        } catch (Exception e) {
            throw new IllegalStateException("failed to clear agent definition test data", e);
        }
    }

    @Test
    void insertFindCountReplaceAndDelete() {
        var owned = definition("agent-owned", "user-1", AgentStatus.DRAFT);
        var system = definition("agent-system", "system", AgentStatus.PUBLISHED);
        system.systemDefault = Boolean.TRUE;
        store.insert(owned);
        store.insert(system);

        assertEquals("agent-owned", store.get("agent-owned").orElseThrow().id);
        assertEquals(List.of("agent-owned"), ids(store.find(direct("user-1"))));
        assertEquals(2, store.find(visible("user-1")).size());
        assertEquals(1, store.count(countQuery(AgentStatus.PUBLISHED)));

        owned.name = "renamed";
        store.replace(owned);
        assertEquals("renamed", store.get("agent-owned").orElseThrow().name);

        store.delete("agent-owned");
        assertTrue(store.get("agent-owned").isEmpty());
    }

    @Test
    void insertIfAbsentDoesNotOverwriteAndUpsertDoes() {
        var agent = definition("agent-upsert", "user-1", AgentStatus.DRAFT);
        assertTrue(store.insertIfAbsent(agent));
        agent.name = "changed";
        assertFalse(store.insertIfAbsent(agent));
        assertEquals("agent-upsert", store.get("agent-upsert").orElseThrow().name);

        store.upsert(agent);
        assertEquals("changed", store.get("agent-upsert").orElseThrow().name);

        var created = definition("agent-created", "user-1", AgentStatus.DRAFT);
        store.upsert(created);
        assertTrue(store.get("agent-created").isPresent());
    }

    private AgentDefinitionQuery direct(String userId) {
        var query = new AgentDefinitionQuery();
        query.scope = AgentDefinitionQuery.Scope.DIRECT;
        query.userId = userId;
        return query;
    }

    private AgentDefinitionQuery visible(String userId) {
        var query = new AgentDefinitionQuery();
        query.scope = AgentDefinitionQuery.Scope.VISIBLE;
        query.userId = userId;
        return query;
    }

    private AgentDefinitionQuery countQuery(AgentStatus status) {
        var query = new AgentDefinitionQuery();
        query.status = status.name();
        return query;
    }

    private List<String> ids(List<AgentDefinition> definitions) {
        return definitions.stream().map(definition -> definition.id).toList();
    }

    private AgentDefinition definition(String id, String userId, AgentStatus status) {
        var definition = new AgentDefinition();
        definition.id = id;
        definition.userId = userId;
        definition.name = id;
        definition.nameKey = id;
        definition.type = DefinitionType.AGENT;
        definition.status = status;
        definition.createdAt = ZonedDateTime.now();
        definition.updatedAt = definition.createdAt;
        return definition;
    }
}
