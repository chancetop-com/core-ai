package ai.core.server.a2a;

import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Optional task execution wiring for local A2A task creation.
 * <p>
 * {@code source} is stamped on the session the task runs in ({@code a2a} by default, {@code hub}
 * for the Agent Hub surface) so a conversation started from the hub is distinguishable.
 * {@code waitTimeout} only applies to the caller-driven send path: A2A itself cancels a task that
 * outlives its 5 minute protocol wait, while the Agent Hub keeps a timed-out task running for the
 * caller to poll instead.
 *
 * @author xander
 */
public final class ServerA2ATaskOptions {
    static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMinutes(5);

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

    /** Options for a caller that blocks for at most {@code waitTimeout} and polls afterwards. */
    public static ServerA2ATaskOptions wait(Duration waitTimeout) {
        var options = new ServerA2ATaskOptions();
        options.waitTimeout = waitTimeout;
        return options;
    }

    /** Options for a caller that returns as soon as the task is submitted. */
    public static ServerA2ATaskOptions detached() {
        var options = new ServerA2ATaskOptions();
        options.detach = true;
        return options;
    }

    String taskId;
    String source;
    Duration waitTimeout;
    boolean detach;
    Consumer<StreamResponse> streamSender;
    Runnable closeStream;
    CompletableFuture<Task> syncFuture;

    public ServerA2ATaskOptions source(String source) {
        this.source = source;
        return this;
    }

    boolean isDetached() {
        return detach;
    }

    String effectiveSource() {
        return source != null && !source.isBlank() ? source : "a2a";
    }

    Duration effectiveWaitTimeout() {
        return waitTimeout != null ? waitTimeout : DEFAULT_WAIT_TIMEOUT;
    }
}
