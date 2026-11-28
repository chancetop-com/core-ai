package ai.core.server.skill.telemetry;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL authoritative store for skill runtime telemetry and papercuts.
 *
 * @author stephen
 */
public class PgSkillTelemetryStore implements SkillTelemetryStore {
    private static final String STATS_TABLE = "app.skill_runtime_stats";
    private static final String BATCHES_TABLE = "app.skill_telemetry_batches";
    private static final String PAPERCUTS_TABLE = "app.skill_papercuts";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static final String SELECT_STATS = "SELECT skill_id, package_digest, invoke_count, resource_read_count,"
            + " materialize_fail_count, attributed_script_calls, attributed_script_errors, last_invoked_at, updated_at"
            + " FROM " + STATS_TABLE;

    private static final String SELECT_PAPERCUTS = "SELECT id, skill_id, qualified_name, package_digest, dedup_key,"
            + " category, status, summary, latest_observed_error, suggested_fix, occurrence_count,"
            + " reporter_user_id, latest_session_id, resolved_by, resolved_at, created_at, last_seen_at"
            + " FROM " + PAPERCUTS_TABLE;

    private static final String UPSERT_STAT_SQL = "INSERT INTO " + STATS_TABLE
            + " (skill_id, package_digest, invoke_count, resource_read_count, materialize_fail_count,"
            + " attributed_script_calls, attributed_script_errors, last_invoked_at, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT (skill_id, package_digest) DO UPDATE SET"
            + " invoke_count = " + STATS_TABLE + ".invoke_count + EXCLUDED.invoke_count,"
            + " resource_read_count = " + STATS_TABLE + ".resource_read_count + EXCLUDED.resource_read_count,"
            + " materialize_fail_count = " + STATS_TABLE + ".materialize_fail_count + EXCLUDED.materialize_fail_count,"
            + " attributed_script_calls = " + STATS_TABLE + ".attributed_script_calls + EXCLUDED.attributed_script_calls,"
            + " attributed_script_errors = " + STATS_TABLE + ".attributed_script_errors + EXCLUDED.attributed_script_errors,"
            + " last_invoked_at = GREATEST(" + STATS_TABLE + ".last_invoked_at, EXCLUDED.last_invoked_at),"
            + " updated_at = EXCLUDED.updated_at";

    private static final String INCREMENT_PAPERCUT_SQL = "UPDATE " + PAPERCUTS_TABLE
            + " SET occurrence_count = LEAST(occurrence_count + 1, 2147483647),"
            + " last_seen_at = ?,"
            + " latest_observed_error = COALESCE(?, latest_observed_error),"
            + " suggested_fix = COALESCE(?, suggested_fix),"
            + " latest_session_id = COALESCE(?, latest_session_id)"
            + " WHERE skill_id = ? AND dedup_key = ? AND status IN (?, ?)";

    @Inject
    public Database database;

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean recordBatch(String batchId, ZonedDateTime expiresAt, List<SkillTelemetryDelta> items) {
        var now = ZonedDateTime.now();
        var merged = mergeAndSort(items);
        try (var transaction = database.beginTransaction()) {
            int inserted = database.execute(
                    "INSERT INTO " + BATCHES_TABLE + " (batch_id, created_at, expires_at) VALUES (?, ?, ?)"
                            + " ON CONFLICT (batch_id) DO NOTHING",
                    batchId, now, expiresAt);
            if (inserted == 0) {
                transaction.commit();
                return false;
            }
            for (var delta : merged) {
                executeUpsert(delta, now);
            }
            transaction.commit();
            return true;
        }
    }

    @Override
    public void recordDeltas(List<SkillTelemetryDelta> items) {
        var merged = mergeAndSort(items);
        if (merged.isEmpty()) return;
        var now = ZonedDateTime.now();
        try (var transaction = database.beginTransaction()) {
            for (var delta : merged) {
                executeUpsert(delta, now);
            }
            transaction.commit();
        }
    }

    @Override
    public List<SkillRuntimeStatRecord> getStats(String skillId) {
        return database.select(SELECT_STATS + " WHERE skill_id = ? ORDER BY updated_at DESC, package_digest ASC",
                        PgSkillRuntimeStatRow.class, skillId)
                .stream()
                .map(this::toStatRecord)
                .toList();
    }

    @Override
    public List<SkillPapercutRecord> listPapercuts(String skillId, SkillPapercutStatus status) {
        if (status != null) {
            return database.select(SELECT_PAPERCUTS
                                    + " WHERE skill_id = ? AND status = ? ORDER BY last_seen_at DESC, id DESC",
                            PgSkillPapercutRow.class, skillId, status.name())
                    .stream()
                    .map(this::toPapercutRecord)
                    .toList();
        }
        return database.select(SELECT_PAPERCUTS + " WHERE skill_id = ? ORDER BY last_seen_at DESC, id DESC",
                        PgSkillPapercutRow.class, skillId)
                .stream()
                .map(this::toPapercutRecord)
                .toList();
    }

    @Override
    public Optional<SkillPapercutRecord> getPapercut(String skillId, String papercutId) {
        return database.selectOne(SELECT_PAPERCUTS + " WHERE skill_id = ? AND id = ?",
                        PgSkillPapercutRow.class, skillId, papercutId)
                .map(this::toPapercutRecord);
    }

    @Override
    public SkillPapercutRecord upsertPapercut(SkillPapercutInput input) {
        var now = ZonedDateTime.now();
        var digest = input.packageDigest() == null ? "" : input.packageDigest();
        var category = input.category() == null ? SkillPapercutCategory.OTHER : input.category();
        var dedupKey = SkillPapercutDedup.dedupKey(input.skillId(), digest, category, input.summary());

        int updated = incrementActivePapercut(input, dedupKey, now);
        if (updated > 0) {
            return findActivePapercut(input.skillId(), dedupKey);
        }

        var id = UUID.randomUUID().toString();
        try {
            database.execute(
                    "INSERT INTO " + PAPERCUTS_TABLE
                            + " (id, skill_id, qualified_name, package_digest, dedup_key, category, status,"
                            + " summary, latest_observed_error, suggested_fix, occurrence_count,"
                            + " reporter_user_id, latest_session_id, created_at, last_seen_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)",
                    id, input.skillId(), input.qualifiedName(), digest, dedupKey, category.name(),
                    SkillPapercutStatus.OPEN.name(), input.summary(), input.observedError(),
                    input.suggestedFix(), input.reporterUserId(), input.sessionId(), now, now);
            return getPapercut(input.skillId(), id).orElseThrow();
        } catch (RuntimeException e) {
            if (!isUniqueViolation(e)) throw e;
            incrementActivePapercut(input, dedupKey, now);
            return findActivePapercut(input.skillId(), dedupKey);
        }
    }

    @Override
    public Optional<SkillPapercutRecord> updatePapercutStatus(String skillId, String papercutId,
                                                              SkillPapercutStatus status, String actorUserId) {
        var now = ZonedDateTime.now();
        String resolvedBy = status == SkillPapercutStatus.OPEN ? null : actorUserId;
        ZonedDateTime resolvedAt = status == SkillPapercutStatus.OPEN ? null : now;
        int updated = database.execute(
                "UPDATE " + PAPERCUTS_TABLE + " SET status = ?, resolved_by = ?, resolved_at = ?"
                        + " WHERE skill_id = ? AND id = ?",
                status.name(), resolvedBy, resolvedAt, skillId, papercutId);
        if (updated == 0) return Optional.empty();
        return getPapercut(skillId, papercutId);
    }

    @Override
    public int purgeExpiredBatches(ZonedDateTime now) {
        return database.execute("DELETE FROM " + BATCHES_TABLE + " WHERE expires_at <= ?", now);
    }

    @Override
    public int purgeClosedPapercutsBefore(ZonedDateTime cutoff) {
        return database.execute(
                "DELETE FROM " + PAPERCUTS_TABLE + " WHERE status IN (?, ?) AND last_seen_at <= ?",
                SkillPapercutStatus.RESOLVED.name(), SkillPapercutStatus.IGNORED.name(), cutoff);
    }

    private int incrementActivePapercut(SkillPapercutInput input, String dedupKey, ZonedDateTime now) {
        return database.execute(INCREMENT_PAPERCUT_SQL,
                now, input.observedError(), input.suggestedFix(), input.sessionId(),
                input.skillId(), dedupKey, SkillPapercutStatus.OPEN.name(), SkillPapercutStatus.IGNORED.name());
    }

    private SkillPapercutRecord findActivePapercut(String skillId, String dedupKey) {
        return database.selectOne(
                        SELECT_PAPERCUTS + " WHERE skill_id = ? AND dedup_key = ? AND status IN (?, ?)",
                        PgSkillPapercutRow.class, skillId, dedupKey,
                        SkillPapercutStatus.OPEN.name(), SkillPapercutStatus.IGNORED.name())
                .map(this::toPapercutRecord)
                .orElseThrow();
    }

    private void executeUpsert(SkillTelemetryDelta delta, ZonedDateTime now) {
        database.execute(UPSERT_STAT_SQL,
                delta.skillId(),
                delta.packageDigest() == null ? "" : delta.packageDigest(),
                delta.invokeDelta(),
                delta.resourceReadDelta(),
                delta.materializeFailDelta(),
                delta.attributedScriptCallsDelta(),
                delta.attributedScriptErrorsDelta(),
                delta.lastInvokedAt(),
                now);
    }

    private List<SkillTelemetryDelta> mergeAndSort(List<SkillTelemetryDelta> items) {
        if (items == null || items.isEmpty()) return List.of();
        var merged = new LinkedHashMap<String, SkillTelemetryDelta>();
        for (var item : items) {
            if (item == null || item.skillId() == null || item.skillId().isBlank() || item.isZero()) continue;
            var digest = item.packageDigest() == null ? "" : item.packageDigest();
            var key = item.skillId() + "\0" + digest;
            merged.merge(key,
                    new SkillTelemetryDelta(item.skillId(), digest, item.invokeDelta(), item.resourceReadDelta(),
                            item.materializeFailDelta(), item.attributedScriptCallsDelta(),
                            item.attributedScriptErrorsDelta(), item.lastInvokedAt()),
                    this::combine);
        }
        var sorted = new ArrayList<>(merged.values());
        sorted.sort(Comparator.comparing(SkillTelemetryDelta::skillId)
                .thenComparing(SkillTelemetryDelta::packageDigest));
        return sorted;
    }

    private SkillTelemetryDelta combine(SkillTelemetryDelta a, SkillTelemetryDelta b) {
        var latest = latestTime(a.lastInvokedAt(), b.lastInvokedAt());
        return new SkillTelemetryDelta(
                a.skillId(),
                a.packageDigest(),
                a.invokeDelta() + b.invokeDelta(),
                a.resourceReadDelta() + b.resourceReadDelta(),
                a.materializeFailDelta() + b.materializeFailDelta(),
                a.attributedScriptCallsDelta() + b.attributedScriptCallsDelta(),
                a.attributedScriptErrorsDelta() + b.attributedScriptErrorsDelta(),
                latest);
    }

    private ZonedDateTime latestTime(ZonedDateTime a, ZonedDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private boolean isUniqueViolation(Throwable error) {
        var current = error;
        while (current != null) {
            if (current instanceof SQLException sql && UNIQUE_VIOLATION_SQL_STATE.equals(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private SkillRuntimeStatRecord toStatRecord(PgSkillRuntimeStatRow row) {
        return new SkillRuntimeStatRecord(
                row.skillId,
                row.packageDigest,
                row.invokeCount == null ? 0L : row.invokeCount,
                row.resourceReadCount == null ? 0L : row.resourceReadCount,
                row.materializeFailCount == null ? 0L : row.materializeFailCount,
                row.attributedScriptCalls == null ? 0L : row.attributedScriptCalls,
                row.attributedScriptErrors == null ? 0L : row.attributedScriptErrors,
                row.lastInvokedAt,
                row.updatedAt);
    }

    private SkillPapercutRecord toPapercutRecord(PgSkillPapercutRow row) {
        var category = SkillPapercutCategory.parse(row.category);
        var status = SkillPapercutStatus.parse(row.status);
        return new SkillPapercutRecord(
                row.id,
                row.skillId,
                row.qualifiedName,
                row.packageDigest,
                row.dedupKey,
                category == null ? SkillPapercutCategory.OTHER : category,
                status == null ? SkillPapercutStatus.OPEN : status,
                row.summary,
                row.latestObservedError,
                row.suggestedFix,
                row.occurrenceCount == null ? 1 : row.occurrenceCount,
                row.reporterUserId,
                row.latestSessionId,
                row.resolvedBy,
                row.resolvedAt,
                row.createdAt,
                row.lastSeenAt);
    }
}
