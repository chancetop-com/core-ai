package ai.core.server.small;

import ai.core.server.domain.GeminiFile;
import ai.core.server.domain.SessionAttachmentRef;
import ai.core.server.domain.SessionFeedback;
import ai.core.server.domain.SessionNotice;
import ai.core.server.domain.UserChannelTarget;
import ai.core.server.domain.UserReport;
import ai.core.server.domain.UserTodo;
import ai.core.server.channel.UserChannelTargetStore;
import ai.core.server.domain.GeminiFileRepository;
import ai.core.server.domain.SessionAttachmentRefRepository;
import ai.core.server.session.SessionFeedbackStore;
import ai.core.server.session.SessionNoticeStore;
import ai.core.server.web.foryou.UserReportStore;
import ai.core.server.web.foryou.UserTodoStore;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/**
 * Copies the remaining small G2 collections into PostgreSQL in one transaction. Each copy is
 * insert-only, so a rolling cutover can be replayed without overwriting PostgreSQL-owned rows.
 *
 * @author stephen
 */
public class SmallCollectionMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SmallCollectionMigrationService.class);
    private static final String MARKER = "small_collections_v1";
    private static final String LOCK_KEY = "small_collection_migration";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<SessionFeedback> sessionFeedbackCollection;
    @Inject
    MongoCollection<SessionNotice> sessionNoticeCollection;
    @Inject
    MongoCollection<UserChannelTarget> userChannelTargetCollection;
    @Inject
    MongoCollection<SessionAttachmentRef> sessionAttachmentRefCollection;
    @Inject
    MongoCollection<GeminiFile> geminiFileCollection;
    @Inject
    MongoCollection<UserReport> userReportCollection;
    @Inject
    MongoCollection<UserTodo> userTodoCollection;

    @Inject
    SessionFeedbackStore sessionFeedbackStore;
    @Inject
    SessionNoticeStore sessionNoticeStore;
    @Inject
    UserChannelTargetStore userChannelTargetStore;
    @Inject
    SessionAttachmentRefRepository sessionAttachmentRefRepository;
    @Inject
    GeminiFileRepository geminiFileRepository;
    @Inject
    UserReportStore userReportStore;
    @Inject
    UserTodoStore userTodoStore;

    @Inject
    Database database;

    public void migrate() {
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgSmallMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("small collection migration skipped, another instance holds the lock");
                return;
            }
            if (migrationDone()) {
                transaction.commit();
                return;
            }
            long feedback = copy(sessionFeedbackCollection, entity -> entity.id, sessionFeedbackStore::insertIfAbsent);
            long notices = copy(sessionNoticeCollection, entity -> entity.id, sessionNoticeStore::insertIfAbsent);
            long targets = copy(userChannelTargetCollection, entity -> entity.id, userChannelTargetStore::insertIfAbsent);
            long attachments = copy(sessionAttachmentRefCollection, entity -> entity.id,
                sessionAttachmentRefRepository::insertIfAbsent);
            long gemini = copy(geminiFileCollection, entity -> entity.id, geminiFileRepository::insertIfAbsent);
            long reports = copy(userReportCollection, entity -> entity.id, userReportStore::insertIfAbsent);
            long todos = copy(userTodoCollection, entity -> entity.id, userTodoStore::insertIfAbsent);
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
            transaction.commit();
            LOGGER.info("small collection migration finished, feedback={}, notices={}, targets={}, attachments={}, "
                + "geminiFiles={}, reports={}, todos={}", feedback, notices, targets, attachments, gemini, reports, todos);
        }
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgSmallMigrationMarkerRow.class, MARKER).isPresent();
    }

    private <T> long copy(MongoCollection<T> collection, Function<T, String> id, Function<T, Boolean> insert) {
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            var page = collection.find(query);
            if (page.isEmpty()) return count;
            for (var entity : page) {
                insert.apply(entity);
                lastId = id.apply(entity);
                count++;
            }
        }
    }
}

