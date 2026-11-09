package ai.core.server.project;



import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectMemberRef;
import ai.core.server.domain.ProjectReportSource;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "SACM_STATIC_ARRAY_CREATED_IN_METHOD", "RFI_SET_ACCESSIBLE"})
class PgProjectStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("project-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgProjectRow.class);
        database.view(PgProjectCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.projects ("
                    + "id text PRIMARY KEY, user_id text NOT NULL, status text NOT NULL, stats_dirty boolean, "
                    + "analysis_status text, last_analyzed_at timestamptz, last_analysis_at timestamptz, "
                    + "created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, archived_at timestamptz, "
                    + "data jsonb NOT NULL)");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize project test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgProjectStore store;

    @BeforeEach
    void setUp() {
        store = new PgProjectStore();
        store.database = DATABASE;
    }

    @Test
    void roundTripsDocumentAndUpdatesThroughRowLock() {
        var id = "p-" + UUID.randomUUID();
        var project = new Project();
        project.id = id;
        project.userId = "user-1";
        project.name = "Project";
        project.status = ProjectService.STATUS_ACTIVE;
        project.statsDirty = Boolean.TRUE;
        project.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        project.updatedAt = project.createdAt;
        var member = new ProjectMemberRef();
        member.type = "agent";
        member.id = "a-1";
        member.name = "Agent";
        project.members = List.of(member);
        var source = new ProjectReportSource();
        source.type = "agent";
        source.id = "a-1";
        source.name = "Agent";
        project.reportSources = List.of(source);
        try {
            store.insert(project);

            var loaded = store.get(id).orElseThrow();
            assertEquals("Project", loaded.name);
            assertEquals("Agent", loaded.members.getFirst().name);

            assertTrue(store.update(id, entity -> {
                entity.name = "Renamed";
                entity.members = List.of();
            }));
            var updated = store.get(id).orElseThrow();
            assertEquals("Renamed", updated.name);
            assertTrue(updated.members == null || updated.members.isEmpty());
            assertEquals(1, store.count("user-1", Boolean.FALSE));
            assertEquals(1, store.list("user-1", Boolean.FALSE, 0, 10).size());
        } finally {
            DATABASE.execute("DELETE FROM app.projects WHERE id = ?", id);
        }
    }

    @Test
    void updateIfHonorsGuard() {
        var id = "p-" + UUID.randomUUID();
        var project = new Project();
        project.id = id;
        project.userId = "user-2";
        project.name = "Guard";
        project.status = ProjectService.STATUS_ACTIVE;
        project.createdAt = ZonedDateTime.parse("2026-11-06T00:00:00Z");
        project.updatedAt = project.createdAt;
        try {
            store.insert(project);
            assertTrue(store.updateIf(id, entity -> "Guard".equals(entity.name), entity -> entity.name = "Changed"));
            assertEquals("Changed", store.get(id).orElseThrow().name);
            assertFalse(store.updateIf(id, entity -> "Guard".equals(entity.name), entity -> entity.name = "Never"));
        } finally {
            DATABASE.execute("DELETE FROM app.projects WHERE id = ?", id);
        }
    }
}
