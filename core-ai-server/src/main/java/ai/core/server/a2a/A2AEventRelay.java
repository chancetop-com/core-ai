package ai.core.server.a2a;

import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.TaskState;
import ai.core.server.messaging.PodRegistry;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Delivers A2A stream events from the task's owner pod to the pod that serves the caller.
 * <p>
 * The owner publishes to local subscribers; a proxy on another pod consumes the owner's
 * {@code /_internal/a2a/events} SSE stream instead of the Redis broadcast used before the
 * PostgreSQL cutover.
 *
 * @author xander
 */
public class A2AEventRelay {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2AEventRelay.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private final Map<String, List<LocalSubscription>> subscribers = new ConcurrentHashMap<>();
    private final PodRegistry podRegistry;
    private final String token;

    public A2AEventRelay(PodRegistry podRegistry, String token) {
        this.podRegistry = podRegistry;
        this.token = token;
    }

    public void publish(String taskId, StreamResponse event) {
        if (taskId == null || taskId.isBlank() || event == null) return;
        var subscriptions = subscribers.get(taskId);
        if (subscriptions == null) return;
        for (var subscription : subscriptions) {
            subscription.send(event);
        }
    }

    public Subscription subscribeLocal(String taskId, Consumer<StreamResponse> eventSender, Runnable closeStream) {
        var subscription = new LocalSubscription(taskId, eventSender, closeStream);
        subscribers.computeIfAbsent(taskId, key -> new CopyOnWriteArrayList<>()).add(subscription);
        return subscription;
    }

    /**
     * Opens the owner's internal event stream before the caller sends the command that starts or
     * resumes the task, so no early event is lost.
     */
    public Subscription subscribeRemote(String ownerPod, String taskId, Consumer<StreamResponse> eventSender,
                                        Runnable closeStream) {
        var address = ownerPod == null ? null : podRegistry.address(ownerPod);
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("A2A owner pod is not addressable, taskId=" + taskId + ", owner=" + ownerPod);
        }
        var subscription = new RemoteSubscription(taskId, address, eventSender, closeStream);
        subscription.start();
        return subscription;
    }

    private boolean isTerminal(StreamResponse event) {
        if (event.statusUpdate == null || event.statusUpdate.status == null) return false;
        var state = event.statusUpdate.status.state;
        return state == TaskState.COMPLETED
                || state == TaskState.FAILED
                || state == TaskState.CANCELED
                || state == TaskState.REJECTED
                || state == TaskState.INPUT_REQUIRED
                || state == TaskState.AUTH_REQUIRED;
    }

    public interface Subscription {
        void close();
    }

    private final class LocalSubscription implements Subscription {
        private final String taskId;
        private final Consumer<StreamResponse> eventSender;
        private final Runnable closeStream;
        private final AtomicBoolean closed = new AtomicBoolean();

        private LocalSubscription(String taskId, Consumer<StreamResponse> eventSender, Runnable closeStream) {
            this.taskId = taskId;
            this.eventSender = eventSender;
            this.closeStream = closeStream;
        }

        private void send(StreamResponse event) {
            if (closed.get()) return;
            eventSender.accept(event);
            if (isTerminal(event)) close();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            var subscriptions = subscribers.get(taskId);
            if (subscriptions != null) {
                subscriptions.remove(this);
                if (subscriptions.isEmpty()) subscribers.remove(taskId, subscriptions);
            }
            if (closeStream != null) closeStream.run();
        }
    }

    private final class RemoteSubscription implements Subscription {
        private final String taskId;
        private final String address;
        private final Consumer<StreamResponse> eventSender;
        private final Runnable closeStream;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean closeStreamCalled = new AtomicBoolean();
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile boolean opened;
        private volatile InputStream body;
        private Thread thread;

        private RemoteSubscription(String taskId, String address, Consumer<StreamResponse> eventSender, Runnable closeStream) {
            this.taskId = taskId;
            this.address = address;
            this.eventSender = eventSender;
            this.closeStream = closeStream;
        }

        private void start() {
            thread = Thread.ofVirtual().name("a2a-event-relay-" + taskId).start(this::run);
            try {
                if (!ready.await(3, TimeUnit.SECONDS) || !opened) {
                    close();
                    throw new IllegalStateException("A2A owner does not expose the directed event stream, taskId=" + taskId
                            + ", owner=" + address);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                throw new IllegalStateException("interrupted while opening the A2A event stream, taskId=" + taskId, e);
            }
        }

        private void run() {
            try {
                var request = HttpRequest.newBuilder(uri())
                        .version(HttpClient.Version.HTTP_1_1)
                        .header("Accept", "text/event-stream")
                        .header("X-Internal-Token", token)
                        .GET()
                        .build();
                var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    LOGGER.warn("A2A event stream unavailable, taskId={}, owner={}, status={}",
                            taskId, address, response.statusCode());
                    ready.countDown();
                    return;
                }
                body = response.body();
                opened = true;
                ready.countDown();
                read(response.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                if (!closed.get()) LOGGER.debug("A2A event stream ended, taskId={}, owner={}, error={}", taskId, address, e.getMessage());
            } catch (RuntimeException e) {
                if (!closed.get()) LOGGER.warn("A2A event stream failed, taskId={}, owner={}", taskId, address, e);
            } finally {
                ready.countDown();
                close();
            }
        }

        private URI uri() {
            return URI.create("http://" + address + "/_internal/a2a/events?task_id="
                    + URLEncoder.encode(taskId, StandardCharsets.UTF_8));
        }

        private void read(InputStream input) throws IOException {
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder data = null;
                while (!closed.get()) {
                    var line = reader.readLine();
                    if (line == null) return;
                    if (line.isEmpty()) {
                        dispatch(data);
                        data = null;
                    } else if (line.startsWith("data:")) {
                        var value = line.substring(5);
                        if (value.startsWith(" ")) value = value.substring(1);
                        if (data == null) data = new StringBuilder(value);
                        else data.append('\n').append(value);
                    }
                }
            }
        }

        private void dispatch(StringBuilder data) {
            if (data == null) return;
            StreamResponse event;
            try {
                event = JsonUtil.fromJson(StreamResponse.class, data.toString());
            } catch (RuntimeException e) {
                LOGGER.warn("failed to parse A2A event, taskId={}", taskId, e);
                return;
            }
            if (event == null) return;
            eventSender.accept(event);
            if (isTerminal(event)) close();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            closeBody();
            if (thread != null) thread.interrupt();
            closeStreamIfNeeded();
        }

        private void closeBody() {
            var current = body;
            body = null;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException e) {
                    LOGGER.debug("failed to close A2A event stream, taskId={}", taskId, e);
                }
            }
        }

        private void closeStreamIfNeeded() {
            if (closeStream != null && closeStreamCalled.compareAndSet(false, true)) closeStream.run();
        }
    }
}
