package ai.core.schedule;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Persistence for {@link ScheduledTask}. Server uses a Mongo-backed implementation,
 * the CLI uses a local file-backed implementation.
 *
 * @author stephen
 */
public interface ScheduledTaskStore {

    /**
     * Persists the task and computes its first {@code nextRunAt}.
     */
    ScheduledTask create(ScheduledTask task);

    List<ScheduledTask> list(String sessionId);

    ScheduledTask get(String id);

    boolean delete(String id);

    /**
     * Returns enabled tasks whose {@code nextRunAt} is due at or before {@code now}.
     */
    List<ScheduledTask> findDue(ZonedDateTime now);

    /**
     * Atomically advances {@code nextRunAt} from {@code expectedNextRunAt} to {@code newNextRunAt}.
     * Returns false when another executor already claimed this occurrence.
     */
    boolean claim(String id, ZonedDateTime expectedNextRunAt, ZonedDateTime newNextRunAt);
}
