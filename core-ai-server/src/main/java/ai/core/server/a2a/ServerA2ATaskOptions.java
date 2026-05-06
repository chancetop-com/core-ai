package ai.core.server.a2a;

import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Optional task execution wiring for local A2A task creation.
 *
 * @author xander
 */
final class ServerA2ATaskOptions {
    static ServerA2ATaskOptions empty() {
        return new ServerA2ATaskOptions();
    }

    static ServerA2ATaskOptions stream(Consumer<StreamResponse> streamSender, Runnable closeStream) {
        var options = new ServerA2ATaskOptions();
        options.streamSender = streamSender;
        options.closeStream = closeStream;
        return options;
    }

    static ServerA2ATaskOptions sync(String taskId, CompletableFuture<Task> syncFuture) {
        var options = new ServerA2ATaskOptions();
        options.taskId = taskId;
        options.syncFuture = syncFuture;
        return options;
    }

    static ServerA2ATaskOptions taskId(String taskId) {
        var options = new ServerA2ATaskOptions();
        options.taskId = taskId;
        return options;
    }

    String taskId;
    Consumer<StreamResponse> streamSender;
    Runnable closeStream;
    CompletableFuture<Task> syncFuture;
}
