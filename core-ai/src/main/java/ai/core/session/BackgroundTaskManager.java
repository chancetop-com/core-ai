package ai.core.session;

import ai.core.agent.Task;
import ai.core.tool.async.AsyncToolTaskExecutor;
import ai.core.tool.subagent.SubagentOutputSinkFactory;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

public class BackgroundTaskManager {

    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private final SessionCommandQueue commandQueue;
    private final SubagentOutputSinkFactory sinkFactory;
    private final ExecutorService executor;

    public BackgroundTaskManager(SessionCommandQueue commandQueue, SubagentOutputSinkFactory sinkFactory) {
        this.commandQueue = commandQueue;
        this.sinkFactory = sinkFactory;
        this.executor = AsyncToolTaskExecutor.getInstance().getExecutor();
    }

    public record TaskHandle(String outputRef, Future<?> future) {}

    public TaskHandle submit(String taskId, Supplier<String> agentRunner) {
        var sink = sinkFactory.create(taskId);
        var outputRef = sink.getReference();
        var future = executor.submit(() -> {
            String status;
            String result = null;
            String error = null;
            try {
                result = agentRunner.get();
                sink.write(result != null ? result : "");
                sink.close();
                status = "completed";
            } catch (Exception e) {
                sink.close();
                status = "failed";
                error = e.getMessage();
            }
            commandQueue.enqueueTaskNotification(buildNotificationXml(taskId, status, outputRef, result, error));
        });
        return new TaskHandle(outputRef, future);
    }

    public void register(Task task) {
        tasks.add(task);
    }

    public void cancelAll() {
        tasks.forEach(Task::cancel);
    }

    public List<Task> getTasks() {
        return Collections.unmodifiableList(tasks);
    }

    public BackgroundTaskManager createChild() {
        return new BackgroundTaskManager(commandQueue, sinkFactory);
    }

    private String buildNotificationXml(String taskId, String status, String outputRef, String result, String error) {
        var resultXml = "completed".equals(status)
                ? "<result>" + result + "</result>"
                : "<error>" + error + "</error>";
        var outputRefXml = outputRef != null ? "<output-ref>" + outputRef + "</output-ref>\n" : "";
        return """
                <task-notification>
                <task-id>%s</task-id>
                <status>%s</status>
                %s%s
                </task-notification>
                """.formatted(taskId, status, outputRefXml, resultXml);
    }
}
