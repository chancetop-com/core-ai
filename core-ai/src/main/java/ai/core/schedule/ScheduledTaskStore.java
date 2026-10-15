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

    /**
     * Every task across sessions. The local engine lists the whole (workspace-spanning) file;
     * the server keeps this unimplemented until its scheduler page grows the same view.
     */
    default List<ScheduledTask> listAll() {
        throw new UnsupportedOperationException();
    }

    /**
     * Merges the provided fields into the stored task and persists the result. Null fields keep
     * the stored value; {@code enabled == true} clears {@code disabledReason}; a changed cron /
     * timezone or a disabled-to-enabled flip recomputes {@code nextRunAt} from now.
     */
    default ScheduledTask update(ScheduledTask task) {
        throw new UnsupportedOperationException();
    }

    /** Records a fired run and updates the task's lastRun* fields. */
    default void recordRun(ScheduledTaskRun run) {
    }

    /** Most recent runs of one task, newest first. */
    default List<ScheduledTaskRun> runs(String taskId, int limit) {
        return List.of();
    }

    /** Removes every task bound to the session; returns how many were removed. */
    default int deleteBySession(String sessionId) {
        return 0;
    }
}
