package ai.core.server.agenthealth;

import ai.core.server.pg.PgDocumentRow;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgAgentReferenceIssueStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-reference-issue-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
        database.view(PgAgentReferenceIssueStore.IdRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_reference_issues ("
                + "id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS agent_reference_issues_active_idx "
                + "ON app.agent_reference_issues ((data ->> 'agentId'), (data ->> 'refType'), (data ->> 'refId')) "
                + "WHERE data ->> 'resolvedAt' IS NULL");
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_health_state ("
                + "id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize agent reference issue test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgAgentReferenceIssueStore store;

    @BeforeEach
    void setUp() {
        store = new PgAgentReferenceIssueStore();
        store.database = DATABASE;
        clearTables();
    }

    private void clearTables() {
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            statement.execute("DELETE FROM app.agent_reference_issues");
            statement.execute("DELETE FROM app.agent_health_state");
        } catch (Exception e) {
            throw new IllegalStateException("failed to clear agent reference issue test data", e);
        }
    }

    @Test
    void upsertPreservesEpisodeAndResolvesIt() {
        var issue = issue("agent-1", "SKILL", "skill-1", "Deleted Skill");
        store.upsertActive(issue);

        var saved = store.findActive("agent-1", "SKILL", "skill-1").orElseThrow();
        assertNotNull(saved.id);
        assertEquals("Deleted Skill", saved.refName);
        assertEquals(1, store.listActive(10, "").size());

        var update = issue("agent-1", "SKILL", "skill-1", "Deleted Skill");
        update.sources = List.of("DRAFT", "PUBLISHED");
        var updated = store.upsertActive(update);
        assertEquals(saved.id, updated.id);
        assertEquals(List.of("DRAFT", "PUBLISHED"), store.findActive("agent-1", "SKILL", "skill-1").orElseThrow().sources);

        var ids = List.of(updated.id);
        store.markNotified(ids, ZonedDateTime.now());
        assertTrue(store.listUnnotified("agent-1").isEmpty());

        store.resolve(ids, "REMOVED", ZonedDateTime.now());
        assertTrue(store.findActive("agent-1", "SKILL", "skill-1").isEmpty());
        assertTrue(store.listActive(10, "").isEmpty());
    }

    @Test
    void stateAndAgentResolutionRoundTrip() {
        var state = store.state();
        assertEquals(Boolean.FALSE, state.backfillDone);
        state.backfillDone = Boolean.TRUE;
        state.cursor = "cursor-1";
        store.saveState(state);
        assertEquals(Boolean.TRUE, store.state().backfillDone);
        assertEquals("cursor-1", store.state().cursor);

        store.upsertActive(issue("agent-1", "API", "api-app:menu", "menu"));
        store.resolveByAgent("agent-1", "AGENT_DELETED", ZonedDateTime.now());
        assertFalse(store.findActive("agent-1", "API", "api-app:menu").isPresent());
    }

    private AgentReferenceIssue issue(String agentId, String type, String refId, String name) {
        var issue = new AgentReferenceIssue();
        issue.agentId = agentId;
        issue.agentOwnerId = "owner";
        issue.agentName = "Agent";
        issue.refType = type;
        issue.refId = refId;
        issue.refName = name;
        issue.reason = "SKILL_NOT_FOUND";
        issue.sources = List.of("DRAFT");
        issue.lastSeenAt = ZonedDateTime.now();
        return issue;
    }
}
