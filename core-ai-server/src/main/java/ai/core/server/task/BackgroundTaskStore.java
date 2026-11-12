package ai.core.server.task;

import ai.core.server.domain.BackgroundTask;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface BackgroundTaskStore {
    Optional<BackgroundTask> get(String id);

    List<BackgroundTask> list(String type, int limit);

    boolean insertIfAbsent(BackgroundTask task);

    boolean retryClaim(String id, String claimedBy, ZonedDateTime startedAt);

    void replace(BackgroundTask task);
}
