package ai.core.agent;

import java.util.concurrent.Future;

public class Task {
    public final String taskId;
    public final String taskName;
    public final String parentTaskId;
    private final Future<?> future;
    private final ExecutionContext subContext;

    public Task(String taskId, String taskName, String parentTaskId, Future<?> future, ExecutionContext subContext) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.parentTaskId = parentTaskId;
        this.future = future;
        this.subContext = subContext;
    }

    public void cancel() {
        if (future != null) {
            future.cancel(true);
        }
        if (subContext != null && subContext.getTaskManager() != null) {
            subContext.getTaskManager().cancelAll();
        }
    }
}
