package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;
import ai.core.server.replay.domain.ReplaySampleStatus;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoReplayRunStore implements ReplayRunStore {
    public static MongoReplayRunStore of(MongoCollection<ReplayRun> collection) {
        var store = new MongoReplayRunStore();
        store.runCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<ReplayRun> runCollection;

    @Override
    public Optional<ReplayRun> get(String id) {
        return runCollection.get(id);
    }

    @Override
    public void insert(ReplayRun run) {
        runCollection.insert(run);
    }

    @Override
    public List<ReplayRun> summaries(String experimentId) {
        var query = new Query();
        query.filter = Filters.eq("experiment_id", experimentId);
        query.sort = Sorts.descending("created_at");
        query.projection = Projections.exclude("request", "samples");
        return runCollection.find(query);
    }

    @Override
    public void deleteByExperiment(String experimentId) {
        runCollection.delete(Filters.eq("experiment_id", experimentId));
    }

    @Override
    public void updateSample(String runId, int sampleIndex, ReplaySampleUpdate update) {
        var sets = new ArrayList<Bson>();
        if (update.status != null) sets.add(Updates.set(path(sampleIndex, "status"), update.status));
        if (update.output != null) sets.add(Updates.set(path(sampleIndex, "output"), update.output));
        if (update.errorMessage != null) sets.add(Updates.set(path(sampleIndex, "error_message"), update.errorMessage));
        if (update.replayTraceId != null) sets.add(Updates.set(path(sampleIndex, "replay_trace_id"), update.replayTraceId));
        if (update.inputTokens != null) sets.add(Updates.set(path(sampleIndex, "input_tokens"), update.inputTokens));
        if (update.outputTokens != null) sets.add(Updates.set(path(sampleIndex, "output_tokens"), update.outputTokens));
        if (update.cachedTokens != null) sets.add(Updates.set(path(sampleIndex, "cached_tokens"), update.cachedTokens));
        if (update.costUsd != null) sets.add(Updates.set(path(sampleIndex, "cost_usd"), update.costUsd));
        if (update.durationMs != null) sets.add(Updates.set(path(sampleIndex, "duration_ms"), update.durationMs));
        if (!sets.isEmpty()) runCollection.update(Filters.eq("_id", runId), Updates.combine(sets));
    }

    @Override
    public void cancelRunningSamples(String runId) {
        var run = runCollection.get(runId).orElse(null);
        if (run == null || run.samples == null) return;
        var updates = new ArrayList<Bson>();
        for (var sample : run.samples) {
            if (sample.status == ReplaySampleStatus.RUNNING) {
                updates.add(Updates.set(path(sample.index, "status"), ReplaySampleStatus.CANCELLED));
            }
        }
        if (!updates.isEmpty()) runCollection.update(Filters.eq("_id", runId), Updates.combine(updates));
    }

    @Override
    public void complete(String runId, ReplayRunStatus status, ZonedDateTime completedAt) {
        runCollection.update(Filters.eq("_id", runId),
            Updates.combine(Updates.set("status", status), Updates.set("completed_at", completedAt)));
    }

    private String path(int sampleIndex, String field) {
        return "samples." + sampleIndex + "." + field;
    }
}
