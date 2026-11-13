package ai.core.server.trace;

import ai.core.server.trace.domain.Trace;
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
 * Copies legacy Mongo traces into PostgreSQL, the same way {@link SpanMigrationService} does it: a
 * background virtual thread pages through {@code _id}, each page commits its own transaction and
 * advances a cursor row so a restart resumes instead of copying everything again.
 *
 * @author stephen
 */
public class TraceMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceMigrationService.class);
    private static final String LOCK_KEY = "trace_migration";
    private static final String MARKER = "traces_v1";
    // a trace carries its full input/output payload, so the page is bounded by rows and by payload
    private static final int PAGE_SIZE = 50;
    private static final long PAGE_PAYLOAD_BYTES = 8L * 1024 * 1024;
    private static final long LOCK_RETRY_DELAY_MS = 5_000L;
    private static final long ERROR_RETRY_DELAY_MS = 30_000L;

    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    TraceStore traceStore;
    @Inject
    Database database;

    public void start(boolean copyLegacyTraces) {
        if (!copyLegacyTraces) {
            LOGGER.info("trace migration skipped by configuration, legacy traces stay in mongo");
            return;
        }
        Thread.ofVirtual().name("trace-migration").start(this::run);
    }

    private void run() {
        LOGGER.info("trace migration started");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (migrationDone()) {
                    LOGGER.info("trace migration already complete, nothing to copy");
                    return;
                }
                var page = page(cursor());
                if (page.isEmpty()) {
                    if (finish()) return;
                    sleep(LOCK_RETRY_DELAY_MS);
                } else if (!copy(page)) {
                    sleep(LOCK_RETRY_DELAY_MS);
                }
            } catch (RuntimeException | Error e) {
                LOGGER.error("trace migration failed, retrying in {}s", ERROR_RETRY_DELAY_MS / 1000, e);
                sleep(ERROR_RETRY_DELAY_MS);
            }
        }
    }

    /**
     * Copies one page and records its last id in the same transaction, so the cursor never runs
     * ahead of the rows that were actually written.
     */
    private boolean copy(List<Trace> page) {
        try (var transaction = database.beginTransaction()) {
            if (!lock()) {
                transaction.rollback();
                return false;
            }
            for (var trace : page) {
                traceStore.insertIfAbsent(trace);
            }
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
            LOGGER.info("trace migration finished");
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

    private List<Trace> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        var traces = traceCollection.find(query);
        long bytes = 0;
        int count = 0;
        for (var trace : traces) {
            bytes += textBytes(trace.input) + textBytes(trace.output);
            count++;
            if (bytes >= PAGE_PAYLOAD_BYTES) break;
        }
        return count >= traces.size() ? traces : traces.subList(0, count);
    }

    private long textBytes(String value) {
        return value == null ? 0 : value.length();
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
