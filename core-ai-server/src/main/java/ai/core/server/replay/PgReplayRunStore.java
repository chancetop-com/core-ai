package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;
import ai.core.server.replay.domain.ReplaySampleStatus;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgReplayRunStore implements ReplayRunStore {
    private static final String TABLE = "app.replay_runs";
    private static final String COLUMNS = "id, experiment_id, user_id, label, model, temperature, reasoning_effort, "
        + "sample_count, status, created_at, completed_at";
    private static final String SELECT = "SELECT " + COLUMNS + ", request, data::text AS data FROM " + TABLE;
    private static final String SUMMARY_SELECT = "SELECT " + COLUMNS + " FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (" + COLUMNS + ", request, data) ";

    private static ReplayRun toRun(PgReplayRunRow row) {
        var run = row.data != null ? JsonUtil.fromJson(ReplayRun.class, row.data) : new ReplayRun();
        run.id = row.id;
        run.experimentId = row.experimentId;
        run.userId = row.userId;
        run.label = row.label;
        run.model = row.model;
        run.temperature = row.temperature;
        run.reasoningEffort = row.reasoningEffort;
        run.sampleCount = row.sampleCount;
        run.status = row.status != null ? ReplayRunStatus.valueOf(row.status.toUpperCase(Locale.ROOT)) : null;
        run.createdAt = row.createdAt;
        run.completedAt = row.completedAt;
        run.request = row.request;
        return run;
    }

    private static String status(ReplayRunStatus status) {
        return status == null ? null : status.name().toLowerCase(Locale.ROOT);
    }

    @Inject
    public Database database;

    @Override
    public Optional<ReplayRun> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgReplayRunRow.class, id)
            .map(PgReplayRunStore::toRun);
    }

    @Override
    public void insert(ReplayRun run) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(run));
    }

    @Override
    public List<ReplayRun> summaries(String experimentId) {
        return database.select(SUMMARY_SELECT + " WHERE experiment_id = ? ORDER BY created_at DESC NULLS LAST, id DESC",
                PgReplayRunRow.class, experimentId)
            .stream().map(PgReplayRunStore::toRun).toList();
    }

    @Override
    public void deleteByExperiment(String experimentId) {
        database.execute("DELETE FROM " + TABLE + " WHERE experiment_id = ?", experimentId);
    }

    @Override
    public void updateSample(String runId, int sampleIndex, ReplaySampleUpdate update) {
        var json = "data";
        var params = new ArrayList<>();
        if (update.status != null) {
            json = jsonbSet(json, sampleIndex, "status", "?::text", params, update.status.name());
        }
        if (update.output != null) {
            json = jsonbSet(json, sampleIndex, "output", "?::text", params, JsonSanitizer.text(update.output));
        }
        if (update.errorMessage != null) {
            json = jsonbSet(json, sampleIndex, "errorMessage", "?::text", params, JsonSanitizer.text(update.errorMessage));
        }
        if (update.replayTraceId != null) {
            json = jsonbSet(json, sampleIndex, "replayTraceId", "?::text", params, update.replayTraceId);
        }
        if (update.inputTokens != null) {
            json = jsonbSet(json, sampleIndex, "inputTokens", "?::bigint", params, update.inputTokens);
        }
        if (update.outputTokens != null) {
            json = jsonbSet(json, sampleIndex, "outputTokens", "?::bigint", params, update.outputTokens);
        }
        if (update.cachedTokens != null) {
            json = jsonbSet(json, sampleIndex, "cachedTokens", "?::bigint", params, update.cachedTokens);
        }
        if (update.costUsd != null) {
            json = jsonbSet(json, sampleIndex, "costUsd", "?::double precision", params, update.costUsd);
        }
        if (update.durationMs != null) {
            json = jsonbSet(json, sampleIndex, "durationMs", "?::bigint", params, update.durationMs);
        }
        if (params.isEmpty()) return;
        params.add(runId);
        database.execute("UPDATE " + TABLE + " SET data = " + json + " WHERE id = ?", params.toArray());
    }

    @Override
    public void cancelRunningSamples(String runId) {
        var run = get(runId).orElse(null);
        if (run == null || run.samples == null) return;
        var json = "data";
        var params = new ArrayList<>();
        for (var sample : run.samples) {
            if (sample.status == ReplaySampleStatus.RUNNING) {
                json = jsonbSet(json, sample.index, "status", "?::text", params,
                    ReplaySampleStatus.CANCELLED.name());
            }
        }
        if (params.isEmpty()) return;
        params.add(runId);
        database.execute("UPDATE " + TABLE + " SET data = " + json + " WHERE id = ?", params.toArray());
    }

    @Override
    public void complete(String runId, ReplayRunStatus status, ZonedDateTime completedAt) {
        database.execute("UPDATE " + TABLE + " SET status = ?, completed_at = ? WHERE id = ?",
            status(status), completedAt, runId);
    }

    private String jsonbSet(String json, int sampleIndex, String field, String cast, List<Object> params, Object value) {
        params.add("samples," + sampleIndex + "," + field);
        params.add(value);
        return "jsonb_set(" + json + ", string_to_array(?, chr(44)), to_jsonb(" + cast + "))";
    }

    private Object[] params(ReplayRun run) {
        return new Object[]{
            run.id,
            run.experimentId,
            run.userId,
            run.label,
            run.model,
            run.temperature,
            run.reasoningEffort,
            run.sampleCount,
            status(run.status),
            run.createdAt,
            run.completedAt,
            JsonSanitizer.text(run.request),
            JsonSanitizer.json(JsonUtil.toJson(run))
        };
    }
}
