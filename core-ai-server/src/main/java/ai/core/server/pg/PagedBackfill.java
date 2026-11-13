package ai.core.server.pg;

import ai.core.server.trace.PgMigrationProgressRow;
import ai.core.server.trace.PgSpanMigrationLockRow;
import ai.core.server.trace.PgSpanMigrationMarkerRow;
import core.framework.db.Database;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Paged copy of one Mongo collection into PostgreSQL, shared by the G5c backfills.
 *
 * <p>Each page commits its own transaction, holds a transaction-scoped advisory lock so only one
 * replica copies at a time, and records its last key in {@code app.migration_progress}, so a restart
 * resumes instead of copying everything again. The marker row in {@code app.g2_migration_markers}
 * means finished. A page that PostgreSQL rejects falls back to row-by-row copying, where a single bad
 * row is logged and skipped rather than blocking the whole backfill.
 *
 * @author stephen
 */
public abstract class PagedBackfill<T> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PagedBackfill.class);
    private static final long LOCK_RETRY_DELAY_MS = 5_000L;
    private static final long ERROR_RETRY_DELAY_MS = 30_000L;

    @Inject
    protected Database database;

    protected abstract String name();

    protected abstract String marker();

    protected abstract String lockKey();

    protected abstract List<T> page(String cursor);

    protected abstract String key(T row);

    protected abstract void write(List<T> page);

    protected abstract void writeOne(T row);

    public void start(boolean copy) {
        if (!copy) {
            LOGGER.info("{} skipped by configuration, legacy documents stay in mongo", name());
            return;
        }
        Thread.ofVirtual().name(name()).start(this::run);
    }

    private void run() {
        LOGGER.info("{} started", name());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (migrationDone()) {
                    LOGGER.info("{} already complete, nothing to copy", name());
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
                LOGGER.error("{} failed, retrying in {}s", name(), ERROR_RETRY_DELAY_MS / 1000, e);
                sleep(ERROR_RETRY_DELAY_MS);
            }
        }
    }

    private boolean copy(List<T> page) {
        var transaction = database.beginTransaction();
        if (!lock()) {
            transaction.rollback();
            return false;
        }
        try {
            write(page);
            saveCursor(key(page.getLast()));
            transaction.commit();
            return true;
        } catch (RuntimeException e) {
            transaction.rollback();
            LOGGER.warn("{} page from {} rejected, retrying row by row", name(), key(page.getFirst()), e);
        }
        copyRowByRow(page);
        return true;
    }

    /**
     * Each row commits on its own so a bad row only costs that row. The cursor still advances past
     * skipped rows, otherwise the same page would be retried forever.
     */
    private void copyRowByRow(List<T> page) {
        int copied = 0;
        int skipped = 0;
        for (var row : page) {
            String rowKey = key(row);
            try (var transaction = database.beginTransaction()) {
                writeOne(row);
                saveCursor(rowKey);
                transaction.commit();
                copied++;
            } catch (RuntimeException e) {
                skipped++;
                LOGGER.warn("{} skipped row, id={}, error={}", name(), rowKey, e.getMessage());
                advanceCursor(rowKey);
            }
        }
        LOGGER.info("{} page copied row by row, copied={}, skipped={}", name(), copied, skipped);
    }

    private void advanceCursor(String rowKey) {
        try (var transaction = database.beginTransaction()) {
            saveCursor(rowKey);
            transaction.commit();
        }
    }

    private boolean finish() {
        try (var transaction = database.beginTransaction()) {
            if (!lock()) {
                transaction.rollback();
                return false;
            }
            database.execute("INSERT INTO app.g2_migration_markers (id, completed_at) VALUES (?, now())"
                + " ON CONFLICT DO NOTHING", marker());
            database.execute("DELETE FROM app.migration_progress WHERE id = ?", marker());
            transaction.commit();
            LOGGER.info("{} finished", name());
            return true;
        }
    }

    protected boolean lock() {
        var lock = database.selectOne("SELECT pg_try_advisory_xact_lock(hashtext(?)) AS acquired",
            PgSpanMigrationLockRow.class, lockKey()).orElse(null);
        return lock != null && Boolean.TRUE.equals(lock.acquired);
    }

    private boolean migrationDone() {
        return database.selectOne("SELECT id FROM app.g2_migration_markers WHERE id = ?",
            PgSpanMigrationMarkerRow.class, marker()).isPresent();
    }

    private String cursor() {
        return database.selectOne("SELECT last_key FROM app.migration_progress WHERE id = ?",
            PgMigrationProgressRow.class, marker()).map(row -> row.lastKey).orElse("");
    }

    protected void saveCursor(String lastKey) {
        database.execute("INSERT INTO app.migration_progress (id, last_key, updated_at) VALUES (?, ?, now())"
            + " ON CONFLICT (id) DO UPDATE SET last_key = EXCLUDED.last_key, updated_at = now()", marker(), lastKey);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
