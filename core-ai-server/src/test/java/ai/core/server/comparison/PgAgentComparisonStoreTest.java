package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.ComparisonStatus;
import ai.core.server.comparison.domain.ComparisonVote;
import ai.core.server.comparison.domain.ComparisonVoteChoice;
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
class PgAgentComparisonStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("agent-comparison-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgAgentComparisonRow.class);
        database.view(PgComparisonVoteRow.class);
        database.view(PgComparisonVoteCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.agent_comparisons ("
                + "id text PRIMARY KEY, agent_id text, agent_name text, status text, created_by text, "
                + "created_at timestamptz, started_at timestamptz, ended_at timestamptz, data jsonb NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS app.comparison_votes ("
                + "id text PRIMARY KEY, comparison_id text NOT NULL, pair_id text, session_a text NOT NULL, "
                + "session_b text, turn bigint NOT NULL, chosen text, user_id text, comment text, message_a text, message_b text, created_at timestamptz NOT NULL, "
                + "data jsonb NOT NULL, CONSTRAINT comparison_votes_unique UNIQUE (comparison_id, session_a, turn))");
            statement.execute("ALTER TABLE app.comparison_votes ADD COLUMN IF NOT EXISTS comment text");
            statement.execute("ALTER TABLE app.comparison_votes ADD COLUMN IF NOT EXISTS message_a text");
            statement.execute("ALTER TABLE app.comparison_votes ADD COLUMN IF NOT EXISTS message_b text");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize comparison test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTables() {
        DATABASE.execute("DELETE FROM app.agent_comparisons");
        DATABASE.execute("DELETE FROM app.comparison_votes");
    }

    @Test
    void comparisonFindByAgentCreatorParticipantAndStatus() {
        var store = new PgAgentComparisonStore();
        store.database = DATABASE;
        var comparison = comparison("cmp-1");
        store.insert(comparison);

        var byAgent = new AgentComparisonQuery();
        byAgent.agentId = "agent-1";
        assertEquals(1, store.find(byAgent).size());
        var byCreator = new AgentComparisonQuery();
        byCreator.createdBy = "user-1";
        assertEquals(1, store.find(byCreator).size());
        var byParticipant = new AgentComparisonQuery();
        byParticipant.participantUserId = "user-2";
        assertEquals(1, store.find(byParticipant).size());
        var running = new AgentComparisonQuery();
        running.agentId = "agent-1";
        running.status = ComparisonStatus.RUNNING;
        assertEquals("cmp-1", store.findOne(running).orElseThrow().id);

        comparison.status = ComparisonStatus.ENDED;
        store.replace(comparison);
        assertEquals(ComparisonStatus.ENDED, store.get("cmp-1").orElseThrow().status);
        store.delete("cmp-1");
        assertTrue(store.get("cmp-1").isEmpty());
    }

    @Test
    void voteUpsertAndCounts() {
        var store = new PgComparisonVoteStore();
        store.database = DATABASE;
        var vote = vote("vote-1");
        assertTrue(store.insertIfAbsent(vote));
        assertFalse(store.insertIfAbsent(vote));
        assertEquals("vote-1", store.find("cmp-1", "session-a", 1L).orElseThrow().id);
        assertEquals(1, store.findByComparison("cmp-1").size());
        assertEquals(1, store.findBySessionA("cmp-1", "session-a").size());
        assertEquals(1, store.countBySessionA("cmp-1", "session-a"));
        vote.chosen = ComparisonVoteChoice.B;
        store.replace(vote);
        assertEquals(ComparisonVoteChoice.B, store.find("cmp-1", "session-a", 1L).orElseThrow().chosen);
    }

    private AgentComparison comparison(String id) {
        var comparison = new AgentComparison();
        comparison.id = id;
        comparison.agentId = "agent-1";
        comparison.agentName = "agent";
        comparison.status = ComparisonStatus.RUNNING;
        comparison.createdBy = "user-1";
        comparison.participantUserIds = List.of("user-1", "user-2");
        comparison.createdAt = ZonedDateTime.now();
        comparison.updatedAt = comparison.createdAt;
        return comparison;
    }

    private ComparisonVote vote(String id) {
        var vote = new ComparisonVote();
        vote.id = id;
        vote.comparisonId = "cmp-1";
        vote.sessionA = "session-a";
        vote.turn = 1L;
        vote.chosen = ComparisonVoteChoice.A;
        vote.createdAt = ZonedDateTime.now();
        return vote;
    }
}


