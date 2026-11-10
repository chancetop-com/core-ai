package ai.core.server.small;

import ai.core.server.channel.PgUserChannelTargetStore;
import ai.core.server.domain.GeminiFile;
import ai.core.server.domain.PgGeminiFileRepository;
import ai.core.server.domain.PgSessionAttachmentRefRepository;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionFeedback;
import ai.core.server.domain.SessionNotice;
import ai.core.server.domain.UserReport;
import ai.core.server.domain.UserTodo;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.session.PgSessionFeedbackStore;
import ai.core.server.session.PgSessionNoticeStore;
import ai.core.server.web.foryou.PgForYouCountRow;
import ai.core.server.web.foryou.PgUserReportStore;
import ai.core.server.web.foryou.PgUserTodoStore;
import core.framework.internal.db.DatabaseImpl;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
@SuppressFBWarnings("REC_CATCH_EXCEPTION")
class PgSmallCollectionStoresTest {
    private static final DatabaseImpl DATABASE = database();

    private static DatabaseImpl database() {
        var database = new DatabaseImpl("small-collection-test");
        database.url(System.getenv("CORE_AI_PG_TEST_URL"));
        database.user = System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai");
        database.password = System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai");
        database.view(PgDocumentRow.class);
        database.view(PgForYouCountRow.class);
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
            for (var table : List.of("session_feedback", "session_notices", "user_channel_targets",
                    "session_attachment_ref", "gemini_files", "user_reports", "user_todos")) {
                statement.execute("CREATE TABLE IF NOT EXISTS app." + table
                    + " (id text PRIMARY KEY, data jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to initialize small collection test schema", e);
        }
    }

    @AfterAll
    static void closeDatabase() {
        DATABASE.close();
    }

    private PgSessionFeedbackStore feedbackStore;
    private PgSessionNoticeStore noticeStore;
    private PgUserChannelTargetStore targetStore;
    private PgSessionAttachmentRefRepository attachmentRepository;
    private PgGeminiFileRepository geminiRepository;
    private PgUserReportStore reportStore;
    private PgUserTodoStore todoStore;

    @BeforeEach
    void setUp() {
        feedbackStore = new PgSessionFeedbackStore();
        feedbackStore.database = DATABASE;
        noticeStore = new PgSessionNoticeStore();
        noticeStore.database = DATABASE;
        targetStore = new PgUserChannelTargetStore();
        targetStore.database = DATABASE;
        attachmentRepository = new PgSessionAttachmentRefRepository();
        attachmentRepository.database = DATABASE;
        geminiRepository = new PgGeminiFileRepository();
        geminiRepository.database = DATABASE;
        reportStore = new PgUserReportStore();
        reportStore.database = DATABASE;
        todoStore = new PgUserTodoStore();
        todoStore.database = DATABASE;
        try (var connection = DriverManager.getConnection(
                System.getenv("CORE_AI_PG_TEST_URL"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_USER", "core_ai"),
                System.getenv().getOrDefault("CORE_AI_PG_TEST_PASSWORD", "core_ai"));
             var statement = connection.createStatement()) {
            for (var table : List.of("session_feedback", "session_notices", "user_channel_targets",
                    "session_attachment_ref", "gemini_files", "user_reports", "user_todos")) {
                statement.execute("TRUNCATE app." + table);
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to reset small collection test tables", e);
        }
    }

    @Test
    void feedbackAndNoticesRoundTrip() {
        var feedback = new SessionFeedback();
        feedback.id = UUID.randomUUID().toString();
        feedback.sessionId = "s-1";
        feedback.userId = "user-1";
        feedback.createdAt = ZonedDateTime.now();
        feedbackStore.insert(feedback);
        assertFalse(feedbackStore.insertIfAbsent(feedback));

        var notice = notice("user-1", "channel-1", "qqbot:c2c:1", ZonedDateTime.now().minusMinutes(2));
        noticeStore.record(notice);
        assertEquals(List.of(notice.id), noticeStore.unanswered("user-1", "channel-1", "qqbot:c2c:1")
            .stream().map(n -> n.id).toList());

        noticeStore.markAnswered("user-1", "channel-1", "qqbot:c2c:1");
        assertEquals(List.of(), noticeStore.unanswered("user-1", "channel-1", "qqbot:c2c:1"));
    }

    @Test
    void channelTargetsAndAttachmentsRoundTrip() {
        targetStore.record("user-1", "channel-1", "qqbot:c2c:1");
        var target = targetStore.load("user-1", "channel-1");
        assertEquals("qqbot:c2c:1", target.recipient);

        var video = attachment("s-1", "user-1", SessionAttachmentRef.KIND_VIDEO, "video_1", ZonedDateTime.now().minusMinutes(2));
        var sandbox = attachment("s-1", "user-1", SessionAttachmentRef.KIND_SANDBOX, "sandbox_1", ZonedDateTime.now());
        assertTrue(attachmentRepository.insertIfAbsent(video));
        assertTrue(attachmentRepository.insertIfAbsent(sandbox));
        assertFalse(attachmentRepository.insertIfAbsent(video));

        assertEquals(video.id, attachmentRepository.findOwned(video.id, "s-1", "user-1").id);
        assertEquals(List.of(video.id), attachmentRepository.findOwnedByPrefix("video_1", "s-1", "user-1")
            .stream().map(ref -> ref.id).toList());
        assertEquals(List.of(video.id), attachmentRepository.findOwnedVideos("s-1", "user-1")
            .stream().map(ref -> ref.id).toList());
        assertEquals(sandbox.id, attachmentRepository.findSandboxAttachments("s-1", "user-1").getFirst().id);

        attachmentRepository.attachFile(video.id, "file-1");
        assertEquals("file-1", attachmentRepository.findOwned(video.id, "s-1", "user-1").fileId);
    }

    @Test
    void geminiFilesFindBySourceAndReplace() {
        var file = new GeminiFile();
        file.id = UUID.randomUUID().toString();
        file.userId = "user-1";
        file.providerId = "gemini";
        file.upstreamModel = "gemini-2.5";
        file.container = "uploads";
        file.blobName = "a.mp4";
        file.sourceETag = "etag-1";
        file.createdAt = ZonedDateTime.now();
        geminiRepository.insert(file);
        assertFalse(geminiRepository.insertIfAbsent(file));
        assertEquals(file.id, geminiRepository.findBySource("user-1", "gemini", "gemini-2.5", "uploads", "a.mp4", "etag-1").id);

        file.state = GeminiFile.GeminiFileState.ACTIVE;
        geminiRepository.replace(file);
        assertEquals(GeminiFile.GeminiFileState.ACTIVE,
            geminiRepository.findBySource("user-1", "gemini", "gemini-2.5", "uploads", "a.mp4", "etag-1").state);
    }

    @Test
    void reportsAndTodosListAndCount() {
        var report = new UserReport();
        report.id = UUID.randomUUID().toString();
        report.userId = "user-1";
        report.title = "report";
        report.createdAt = ZonedDateTime.now().minusMinutes(1);
        report.updatedAt = ZonedDateTime.now();
        reportStore.insert(report);
        assertEquals(1L, reportStore.count("user-1"));
        assertEquals(report.id, reportStore.list("user-1").getFirst().id);
        assertEquals(report.id, reportStore.recent("user-1", 5).getFirst().id);

        var todo = new UserTodo();
        todo.id = UUID.randomUUID().toString();
        todo.userId = "user-1";
        todo.title = "todo";
        todo.completed = Boolean.FALSE;
        todo.createdAt = ZonedDateTime.now();
        todo.updatedAt = todo.createdAt;
        todoStore.insert(todo);
        assertEquals(1L, todoStore.count("user-1"));
        assertEquals(1L, todoStore.activeCount("user-1"));
        assertEquals(todo.id, todoStore.active("user-1").getFirst().id);
        todo.completed = Boolean.TRUE;
        todoStore.replace(todo);
        assertEquals(0L, todoStore.activeCount("user-1"));
    }

    private SessionNotice notice(String userId, String channelId, String recipient, ZonedDateTime createdAt) {
        var notice = new SessionNotice();
        notice.id = UUID.randomUUID().toString();
        notice.userId = userId;
        notice.channelId = channelId;
        notice.recipient = recipient;
        notice.kind = SessionNotice.KIND_SESSION_COMPLETION;
        notice.sessionId = "s-1";
        notice.answered = Boolean.FALSE;
        notice.createdAt = createdAt;
        return notice;
    }

    private SessionAttachmentRef attachment(String sessionId, String userId, String kind, String id, ZonedDateTime createdAt) {
        var reference = new SessionAttachmentRef();
        reference.id = id;
        reference.sessionId = sessionId;
        reference.userId = userId;
        reference.kind = kind;
        reference.container = "uploads";
        reference.blobName = id;
        reference.createdAt = createdAt;
        return reference;
    }
}

