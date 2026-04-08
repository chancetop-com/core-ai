package ai.core.tool.subagent;

import ai.core.tool.async.AsyncToolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * In-memory registry of running background subagent tasks.
 * Held in ExecutionContext; shared between TaskTool (writer) and Agent (reader).
 */
public class SubagentTaskRegistry {
    private final Map<String, RunningTask> running = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public SubagentTaskRegistry() {
        this.executor = AsyncToolTaskExecutor.getInstance().getExecutor();
    }

    public String submit(String taskId, SubagentOutputSink sink, Supplier<String> agentRunner) {
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                var result = agentRunner.get();
                sink.close();
                return new SubagentResult(taskId, "completed", result, sink.getReference(), null);
            } catch (Exception e) {
                sink.close();
                return new SubagentResult(taskId, "failed", null, sink.getReference(), e.getMessage());
            }
        }, executor);
        running.put(taskId, new RunningTask(future, sink));
        return taskId;
    }

    public boolean hasPending() {
        return running.values().stream().anyMatch(t -> !t.future().isDone());
    }

    /**
     * Non-blocking: returns and removes all tasks that have completed.
     * Called at the start of each Agent chatTurns iteration.
     */
    public List<SubagentResult> drainCompleted() {
        var completed = new ArrayList<SubagentResult>();
        var iter = running.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            if (entry.getValue().future().isDone()) {
                try {
                    completed.add(entry.getValue().future().get());
                } catch (Exception e) {
                    completed.add(new SubagentResult(entry.getKey(), "failed", null,
                            entry.getValue().sink().getReference(), e.getMessage()));
                }
                iter.remove();
            }
        }
        return completed;
    }

    public void shutdown() {
        // executor is shared with AsyncToolTaskExecutor, do not shut it down here
    }

    public record SubagentResult(
            String taskId,
            String status,
            String result,
            String outputRef,
            String error
    ) { }

    private record RunningTask(
            CompletableFuture<SubagentResult> future,
            SubagentOutputSink sink
    ) { }
}
