package ai.core.server.replay;

import ai.core.server.replay.domain.ReplayExperiment;
import ai.core.server.replay.domain.ReplayExperimentOrigin;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ReplayExperimentStore {
    int MAX_LIST_LIMIT = 200;

    Optional<ReplayExperiment> get(String id);

    void insert(ReplayExperiment experiment);

    void replace(ReplayExperiment experiment);

    void delete(String id);

    List<ReplayExperiment> find(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin,
                                int offset, int limit);

    long count(String userId, boolean admin, String agentId, ReplayExperimentOrigin origin);

    void incrementRunCount(String id, ZonedDateTime updatedAt);

    int deleteUntouchedBlankBefore(ZonedDateTime cutoff, String blankDraft);
}

