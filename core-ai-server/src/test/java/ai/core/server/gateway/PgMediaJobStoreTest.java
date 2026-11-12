package ai.core.server.gateway;

import ai.core.server.domain.MediaJob;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgMediaJobStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("media-job-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgMediaJobRow.class);
        database.view(PgMediaJobCountRow.class);
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
            statement.execute("CREATE TABLE IF NOT EXISTS app.media_jobs ("
                + "id text PRIMARY KEY, user_id text, session_id text, agent_run_id text, state text, media_type text, "
                + "cost_source text, file_id text, artifact_state text, artifact_attempts integer, "
                + "artifact_claimed_at timestamptz, artifact_next_attempt_at timestamptz, poster_file_id text, poster_attempted_at timestamptz, "
                + "artifact_stored_at timestamptz, updated_at timestamptz, created_at timestamptz, completed_at timestamptz, "
                + "data jsonb NOT NULL)");
            statement.execute("ALTER TABLE app.media_jobs ADD COLUMN IF NOT EXISTS artifact_next_attempt_at timestamptz");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize media job test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    @BeforeEach
    void clearTable() {
        DATABASE.execute("DELETE FROM app.media_jobs");
    }

    @Test
    void insertListGetAndClaim() {
        var store = new PgMediaJobStore();
        store.database = DATABASE;
        var job = job("video");
        store.insert(job);

        assertEquals("job-1", store.get("job-1").orElseThrow().id);
        assertEquals(1, store.list("video", null, null, 0, 10).total());
        assertEquals(1, store.list("video", null, null, 0, 10).jobs().size());
        assertTrue(store.claimArtifact("job-1"));
        assertFalse(store.claimArtifact("job-1"));

        var stored = store.get("job-1").orElseThrow();
        assertEquals("storing", stored.artifactState);
        assertEquals(1, stored.artifactAttempts);
        assertEquals(job.id, store.findLatestCompleted(null, "user-1", "video").orElseThrow().id);
    }

    @Test
    void replacePersistsMutableFields() {
        var store = new PgMediaJobStore();
        store.database = DATABASE;
        var job = job("image");
        store.insert(job);

        job.fileId = "file-1";
        job.artifactState = "stored";
        job.updatedAt = ZonedDateTime.now();
        store.replace(job);

        var stored = store.get("job-1").orElseThrow();
        assertEquals("file-1", stored.fileId);
        assertEquals("stored", stored.artifactState);
    }

    private MediaJob job(String mediaType) {
        var job = new MediaJob();
        job.id = "job-1";
        job.userId = "user-1";
        job.state = "completed";
        job.mediaType = mediaType;
        job.createdAt = ZonedDateTime.now();
        job.updatedAt = job.createdAt;
        job.completedAt = job.createdAt;
        return job;
    }
}


