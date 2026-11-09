package ai.core.server.pg;



import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import ai.core.server.domain.ProjectReportDraft;
import ai.core.server.domain.ProjectStatsEntity;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.ProjectTargetScan;
import ai.core.server.project.PgProjectAttributionRepository;
import ai.core.server.project.PgProjectCountRow;
import ai.core.server.project.PgProjectEventStore;
import ai.core.server.project.PgProjectReportDraftStore;
import ai.core.server.project.PgProjectStatsStore;
import ai.core.server.project.PgProjectSubjectStore;
import ai.core.server.project.PgProjectTargetScanRepository;
import ai.core.server.project.PgSubjectFileStatsRow;
import core.framework.internal.db.DatabaseImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@EnabledIfEnvironmentVariable(named = "CORE_AI_PG_TEST_URL", matches = ".+")
@SuppressFBWarnings({"REC_CATCH_EXCEPTION", "SACM_STATIC_ARRAY_CREATED_IN_METHOD", "RFI_SET_ACCESSIBLE"})
class PgProjectChildStoreTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("project-child-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
        database.view(PgProjectCountRow.class);
        database.view(PgSubjectFileStatsRow.class);
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
            for (var table : new String[]{"project_subjects", "project_subject_attributions", "project_subject_events",
                "project_target_scans", "project_report_drafts", "project_stats"}) {
                statement.execute("CREATE TABLE IF NOT EXISTS app." + table
                    + " (id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            }
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_test_project_subject_auto ON app.project_subjects "
                + "((data ->> 'projectId'), (data ->> 'nameKey')) WHERE (data ->> 'source') = 'auto'");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_test_project_attr_target ON app.project_subject_attributions "
                + "((data ->> 'subjectId'), (data ->> 'targetType'), (data ->> 'targetId'))");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_test_project_attr_file ON app.project_subject_attributions "
                + "((data ->> 'projectId'), (data ->> 'targetId')) WHERE (data ->> 'targetType') = 'file'");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_test_project_scan ON app.project_target_scans "
                + "((data ->> 'projectId'), (data ->> 'targetType'), (data ->> 'targetId'))");
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize project child test schema", e);
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
    void subjectStoreQueriesAndUpdates() {
        var store = withDatabase(new PgProjectSubjectStore());
        var projectId = "p-" + UUID.randomUUID();
        var subject = subject(projectId, "Acme", "auto");
        subject.nameKey = "acme-" + projectId;
        try {
            assertTrue(store.insertIfAbsent(subject));
            assertFalse(store.insertIfAbsent(subject), "duplicate auto subject must not be inserted twice");
            assertEquals(subject.id, store.findAutoByNameKey(projectId, subject.nameKey).orElseThrow().id);
            assertEquals(1, store.listByProject(projectId).size());
            assertEquals(1, store.countStarted(projectId));
            assertEquals(1, store.listStarted(projectId).size());

            subject.reportRunId = "run-1";
            store.update(subject.id, entity -> {
                entity.summary = "updated";
                entity.reportRunId = "run-1";
            });
            assertEquals("updated", store.get(subject.id).orElseThrow().summary);
            assertTrue(store.listWithReportRunId(10).stream().anyMatch(item -> item.id.equals(subject.id)));
        } finally {
            store.delete(subject.id);
        }
        assertTrue(store.get(subject.id).isEmpty());
    }

    @Test
    void eventStoreQueriesDedupAndDelete() {
        var store = withDatabase(new PgProjectEventStore());
        var projectId = "p-" + UUID.randomUUID();
        var subjectId = "s-" + UUID.randomUUID();
        var at = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        var event = event(projectId, subjectId, ProjectSubjectEvent.TYPE_KPI, "revenue", "10", at);
        try {
            store.insert(event);
            assertEquals(at, store.latestAt(subjectId).orElseThrow());
            assertEquals(1, store.countByProjectAndSubject(projectId, subjectId));
            assertEquals(1, store.listByProjectAndSubject(projectId, subjectId, 0, 10).size());
            assertEquals(1, store.listBySubjectAndType(subjectId, ProjectSubjectEvent.TYPE_KPI, 10).size());
            assertEquals(1, store.listByProject(projectId, subjectId, null, null, null, 10).size());
            assertTrue(store.exists(projectId, subjectId, ProjectSubjectEvent.TYPE_KPI, "revenue", "10", at));
            assertFalse(store.exists(projectId, subjectId, ProjectSubjectEvent.TYPE_KPI, "other", "10", at));
        } finally {
            store.deleteBySubject(subjectId);
        }
        assertEquals(0, store.countByProjectAndSubject(projectId, subjectId));
    }

    @Test
    void draftAndStatsStores() {
        var drafts = withDatabase(new PgProjectReportDraftStore());
        var stats = withDatabase(new PgProjectStatsStore());
        var subjectId = "s-" + UUID.randomUUID();
        var draft = new ProjectReportDraft();
        draft.id = UUID.randomUUID().toString();
        draft.subjectId = subjectId;
        draft.sections = List.of("<style></style>");
        draft.createdAt = ZonedDateTime.now();
        try {
            drafts.insert(draft);
            drafts.update(draft.id, entity -> entity.sections.add("<section></section>"));
            assertEquals(2, drafts.get(draft.id).orElseThrow().sections.size());

            var entity = new ProjectStatsEntity();
            entity.id = "p-" + UUID.randomUUID();
            stats.replace(entity);
            stats.replace(entity);
            assertTrue(stats.get(entity.id).isPresent());
        } finally {
            drafts.deleteBySubject(subjectId);
        }
        assertTrue(drafts.get(draft.id).isEmpty());
    }

    @Test
    void attributionRepositoryEnforcesFileHomeAndSupportsQueries() {
        var repository = withDatabase(new PgProjectAttributionRepository());
        var projectId = "p-" + UUID.randomUUID();
        var subjectId = "s-" + UUID.randomUUID();
        var file = attribution(projectId, subjectId, "file", "f-1");
        file.targetCreatedAt = ZonedDateTime.parse("2026-11-01T00:00:00Z");
        var session = attribution(projectId, subjectId, "session", "c-1");
        try {
            assertTrue(repository.insertIfAbsent(file));
            assertFalse(repository.insertIfAbsent(file), "duplicate attribution must be ignored");
            var other = attribution(projectId, "s-other", "file", "f-1");
            assertFalse(repository.insertIfAbsent(other), "a file has one home per project");
            assertTrue(repository.fileAttribution(projectId, "f-1").isPresent());
            assertEquals(subjectId, repository.fileSubjects(projectId).get("f-1"));
            assertEquals(1, repository.filedFileCount(projectId, null, null, null));
            assertEquals(1, repository.filedFiles(projectId, null, null, null, 0, 10).size());
            assertEquals(1, repository.fileStatsBySubject(projectId).get(subjectId).count());

            repository.insertIfAbsent(session);
            assertEquals(1, repository.listBySubjectAndType(subjectId, "session").size());
            assertEquals(1, repository.listByProjectAndType(projectId, "session").size());
            assertEquals(1, repository.listByProjectAndTargets(projectId, "session", List.of("c-1")).size());
            assertEquals(2, repository.countUnanalyzed(subjectId));
            repository.update(session.id, row -> row.analyzedAt = ZonedDateTime.now());
            assertEquals(1, repository.countUnanalyzed(subjectId));
            repository.clearAnalyzed(projectId, "session", "c-1");
            assertEquals(2, repository.countUnanalyzed(subjectId));
        } finally {
            repository.deleteBySubject(subjectId);
            repository.deleteBySubject("s-other");
        }
        assertNull(repository.get(file.id).orElse(null));
    }

    @Test
    void scanRepositoryUpsertsAndFindsOldest() {
        var repository = withDatabase(new PgProjectTargetScanRepository());
        var projectId = "p-" + UUID.randomUUID();
        var scan = scan(projectId, ZonedDateTime.parse("2026-11-01T00:00:00Z"));
        try {
            repository.upsert(scan);
            var later = scan(projectId, ZonedDateTime.parse("2026-11-02T00:00:00Z"));
            later.targetId = scan.targetId;
            repository.upsert(later);
            assertEquals(1, repository.listByProjectAndTargets(projectId, "session", List.of(scan.targetId)).size());
            assertEquals(1, repository.listByProject(projectId).size());
            assertEquals(later.materialAt, repository.find(projectId, "session", scan.targetId).orElseThrow().materialAt);
            assertEquals(later.materialAt, repository.oldestMaterialAt(projectId).orElseThrow());
            repository.deleteByProjectAndTarget(projectId, "session", scan.targetId);
            assertTrue(repository.find(projectId, "session", scan.targetId).isEmpty());
        } finally {
            repository.deleteById(scan.id);
        }
    }

    private ProjectSubject subject(String projectId, String name, String source) {
        var subject = new ProjectSubject();
        subject.id = UUID.randomUUID().toString();
        subject.projectId = projectId;
        subject.userId = "user-1";
        subject.name = name;
        subject.status = "started";
        subject.source = source;
        subject.createdAt = ZonedDateTime.now();
        subject.updatedAt = subject.createdAt;
        return subject;
    }

    private ProjectSubjectEvent event(String projectId, String subjectId, String type, String key, String value, ZonedDateTime at) {
        var event = new ProjectSubjectEvent();
        event.id = UUID.randomUUID().toString();
        event.projectId = projectId;
        event.subjectId = subjectId;
        event.type = type;
        event.key = key;
        event.value = value;
        event.at = at;
        return event;
    }

    private ProjectSubjectAttribution attribution(String projectId, String subjectId, String targetType, String targetId) {
        var attribution = new ProjectSubjectAttribution();
        attribution.id = UUID.randomUUID().toString();
        attribution.projectId = projectId;
        attribution.subjectId = subjectId;
        attribution.targetType = targetType;
        attribution.targetId = targetId;
        attribution.createdAt = ZonedDateTime.now();
        return attribution;
    }

    private ProjectTargetScan scan(String projectId, ZonedDateTime materialAt) {
        var scan = new ProjectTargetScan();
        scan.id = UUID.randomUUID().toString();
        scan.projectId = projectId;
        scan.targetType = "session";
        scan.targetId = "c-1";
        scan.materialAt = materialAt;
        scan.scannedAt = ZonedDateTime.now();
        return scan;
    }
}
