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
 * <p>The copy runs after startup rather than inside it. A span can carry megabytes of payload, and
 * the whole history is too large to finish within the startup watchdog and to commit as one
 * transaction. Each page commits its own transaction and advances a cursor row, so a pod restart
 * resumes from the last committed page instead of copying everything again.
 *
 * @author stephen
 */
public class SpanMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpanMigrationService.class);
    private static final String LOCK_KEY = "span_migration";
    private static final String MARKER = "spans_v1";
    // bounded by payload, not just row count: a page is held in the JVM while PostgreSQL writes it
    private static final int PAGE_SIZE = 50;
    private static final long LOCK_RETRY_DELAY_MS = 5_000L;
    private static final long ERROR_RETRY_DELAY_MS = 30_000L;

    @Inject
    MongoCollection<Span> spanCollection;
    @Inject
    SpanStore spanStore;
    @Inject
    Database database;

    public void start(boolean copyLegacySpans) {
        if (!copyLegacySpans) {
            LOGGER.info("span migration skipped by configuration, legacy spans stay archived");
            return;
        }
        Thread.ofVirtual().name("span-migration").start(this::run);
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (migrationDone()) {
                    LOGGER.info("span migration already complete, nothing to copy");
                    return;
                }
                List<Span> page = page(cursor());
                if (page.isEmpty()) {
                    if (finish()) return;
                    sleep(LOCK_RETRY_DELAY_MS);
                } else if (!copy(page)) {
                    sleep(LOCK_RETRY_DELAY_MS);
                }
            } catch (RuntimeException e) {
                LOGGER.error("span migration failed, retrying in {}s", ERROR_RETRY_DELAY_MS / 1000, e);
                sleep(ERROR_RETRY_DELAY_MS);
            }
        }
    }

    /**
     * Copies one page and records its last id in the same transaction, so the cursor never runs
     * ahead of the rows that were actually written.
     */
    private boolean copy(List<Span> page) {
        try (var transaction = database.beginTransaction()) {
            if (!lock()) {
                transaction.rollback();
                return false;
            }
            spanStore.bulkInsert(page);
            saveCursor(page.getLast().id);
            transaction.commit();
            return true;
        }
    }

    private boolean finish() {
        try (var transaction = database.beginTransaction()) {
            if (!lock()) {
                transaction.rollback();
                return false;
            }
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())"
                    + " ON CONFLICT DO NOTHING", MARKER);
            database.execute("DELETE FROM app.migration_progress WHERE id = ?", MARKER);
            transaction.commit();
            LOGGER.info("span migration finished");
            return true;
        }
    }

    private boolean lock() {
        var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
                PgSpanMigrationLockRow.class, LOCK_KEY).orElse(null);
        return lock != null && Boolean.TRUE.equals(lock.acquired);
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
                PgSpanMigrationMarkerRow.class, MARKER).isPresent();
    }

    private String cursor() {
        return database.selectOne("SELECT last_key FROM app.migration_progress WHERE id = ?",
                PgMigrationProgressRow.class, MARKER).map(row -> row.lastKey).orElse("");
    }

    private List<Span> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        return spanCollection.find(query);
    }

    private void saveCursor(String lastKey) {
        database.execute("INSERT INTO app.migration_progress (id, last_key, updated_at) VALUES (?, ?, now())"
                + " ON CONFLICT (id) DO UPDATE SET last_key = EXCLUDED.last_key, updated_at = now()", MARKER, lastKey);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
