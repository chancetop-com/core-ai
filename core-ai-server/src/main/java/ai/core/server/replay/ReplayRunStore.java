package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayRun;
import ai.core.server.replay.domain.ReplayRunStatus;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ReplayRunStore {
    Optional<ReplayRun> get(String id);

    void insert(ReplayRun run);

    List<ReplayRun> summaries(String experimentId);

    void deleteByExperiment(String experimentId);

    void updateSample(String runId, int sampleIndex, ReplaySampleUpdate update);

    void cancelRunningSamples(String runId);

    void complete(String runId, ReplayRunStatus status, ZonedDateTime completedAt);
}
