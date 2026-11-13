package ai.core.server.trace;

import ai.core.server.trace.domain.Span;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.db.Database;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Copies legacy Mongo spans into PostgreSQL. UAT archived its span history to object storage
 * instead, so environments opt out with {@code sys.trace.span.migration=skip}; new spans always
 * go to the active store.
 *
 * @author stephen
 */
public class SpanMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpanMigrationService.class);
    private static final String LOCK_KEY = "span_migration";
    private static final String MARKER = "spans_v1";
    private static final int PAGE_SIZE = 200;

    @Inject
    MongoCollection<Span> spanCollection;
    @Inject
    SpanStore spanStore;
    @Inject
    Database database;

    public void migrate(boolean copyLegacySpans) {
        if (!copyLegacySpans) {
            LOGGER.info("span migration skipped by configuration, legacy spans stay archived");
            return;
        }
        try (var transaction = database.beginTransaction()) {
            var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                    PgSpanMigrationLockRow.class, LOCK_KEY).orElse(null);
            if (lock == null || !Boolean.TRUE.equals(lock.acquired)) {
                transaction.rollback();
                LOGGER.info("span migration skipped, another instance holds the lock");
                return;
            }
            long count = copy();
            insertMarker();
            transaction.commit();
            LOGGER.info("span migration finished, spans={}", count);
        }
    }

    private long copy() {
        if (migrationDone()) return 0;
        long count = 0;
        String lastId = "";
        while (true) {
            var query = new Query();
            query.filter = Filters.gt("_id", lastId);
            query.sort = Sorts.ascending("_id");
            query.limit = PAGE_SIZE;
            List<Span> page = spanCollection.find(query);
            if (page.isEmpty()) break;
            spanStore.bulkInsert(page);
            lastId = page.getLast().id;
            count += page.size();
        }
        return count;
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgSpanMigrationMarkerRow.class, MARKER).isPresent();
    }

    private void insertMarker() {
        if (!migrationDone()) {
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())", MARKER);
        }
    }
}
