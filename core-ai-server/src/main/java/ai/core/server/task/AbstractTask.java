package ai.core.server.task;

/**
 * Base class for background maintenance tasks.
 * Subclasses implement {@link #type()} (used as the task record prefix)
 * and {@link #execute(TaskContext)} (the actual work).
 *
 * @author cyril
 */
public abstract class AbstractTask {

    /**
     * Unique task type identifier, e.g. "TRACE_DAILY_MAINTENANCE".
     * Combined with a date suffix to form the {@code background_tasks._id}.
     */
    public abstract String type();

    /**
     * Execute the task logic. Called by {@link TaskRunner} after successfully
     * inserting a RUNNING record. On normal return the status is set to SUCCESS;
     * any thrown exception sets it to FAILED.
     */
    public abstract void execute(TaskContext ctx);
}
