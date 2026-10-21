package ai.core.session;

import ai.core.telemetry.RecordingSpanProcessor;
import ai.core.tool.subagent.SubagentOutputSink;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundTaskManagerTest {

    @SuppressWarnings("PMD.UseTryWithResources")
    private static void submitAndWait(Tracer tracer, BackgroundTaskManager manager, Span parentSpan) throws Exception {
        var scope = parentSpan.makeCurrent();
        try {
            var handle = manager.submit("deep-research-1", () -> {
                var agentSpan = tracer.spanBuilder("background-agent").startSpan();
                agentSpan.end();
                return "done";
            }, null);
            handle.future().get(5, TimeUnit.SECONDS);
        } finally {
            scope.close();
            parentSpan.end();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void backgroundTaskKeepsSubmittingTraceContext() throws Exception {
        var spans = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spans)
                .build();
        try {
            var openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
            var tracer = openTelemetry.getTracer("test");
            var commandQueue = new SessionCommandQueue();
            var manager = new BackgroundTaskManager(commandQueue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
            var parentSpan = tracer.spanBuilder("task-tool").startSpan();

            submitAndWait(tracer, manager, parentSpan);

            var backgroundAgent = spans.find("background-agent").orElseThrow();
            assertEquals(parentSpan.getSpanContext().getTraceId(), backgroundAgent.getTraceId());
            assertEquals(parentSpan.getSpanContext().getSpanId(), backgroundAgent.getParentSpanId());
        } finally {
            tracerProvider.shutdown();
        }
    }

    @Test
    void largeTaskResultIsTruncatedInNotificationButSinkKeepsFullContent() throws Exception {
        var big = "x".repeat(200_000);
        var sink = new CapturingSink("big-1");
        var commandQueue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(commandQueue, taskId -> sink, "test-session", event -> { });
        var handle = manager.submit("big-1", () -> big, null);
        handle.future().get(5, TimeUnit.SECONDS);

        var batch = commandQueue.drainSameMode();
        assertEquals(SessionCommandQueue.CommandMode.TASK_NOTIFICATION, batch.mode());
        var xml = batch.values().getFirst().value();
        assertTrue(xml.contains("<task-notification>"));
        assertTrue(xml.contains("<output-ref>memory://big-1</output-ref>"));
        assertFalse(xml.contains(big), "full task output must not be injected into the agent notification");
        assertTrue(xml.contains("[Output truncated"), "notification must signal truncation to the agent");

        // The sink keeps the full output so the agent can read parts on demand via the output-ref file.
        assertEquals(big, sink.content);
    }

    @Test
    void cancelBeforeStartRunsTheNeverStartedCallback() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var manager = new BackgroundTaskManager(new SessionCommandQueue(), taskId -> new InMemorySink(taskId), "test-session", event -> { }, executor);
            var blockerStarted = new CountDownLatch(1);
            var blockerGate = new CountDownLatch(1);
            manager.submit("blocker", () -> {
                blockerStarted.countDown();
                awaitQuietly(blockerGate);
                return "blocked";
            }, null);
            assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));

            var ran = new AtomicBoolean(false);
            var neverStarted = new AtomicInteger();
            var queued = manager.submit("queued", () -> {
                ran.set(true);
                return "queued";
            }, null, neverStarted::incrementAndGet);

            assertTrue(manager.cancel("queued"));
            assertEquals(1, neverStarted.get());
            assertTrue(queued.future().isCancelled());
            assertFalse(ran.get());
            blockerGate.countDown();
        }
    }

    @Test
    void cancelAfterStartDoesNotRunTheNeverStartedCallback() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var manager = new BackgroundTaskManager(new SessionCommandQueue(), taskId -> new InMemorySink(taskId), "test-session", event -> { }, executor);
            var started = new CountDownLatch(1);
            var gate = new CountDownLatch(1);
            var finished = new CountDownLatch(1);
            var neverStarted = new AtomicInteger();
            var handle = manager.submit("running", () -> {
                started.countDown();
                awaitQuietly(gate);
                finished.countDown();
                return "done";
            }, null, neverStarted::incrementAndGet);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertTrue(manager.cancel("running"));
            assertEquals(0, neverStarted.get());
            assertTrue(handle.future().isCancelled());
            gate.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void pollAndAwaitExposeTerminalResult() throws Exception {
        var commandQueue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(commandQueue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
        var handle = manager.submit("done-1", () -> "done", null);
        handle.future().get(5, TimeUnit.SECONDS);

        var polled = manager.pollTask("done-1");
        assertTrue(polled.isCompleted());
        assertEquals("done", polled.getResult());

        var awaited = manager.awaitTask("done-1", 1000);
        assertTrue(awaited.isCompleted());
        assertEquals("done", awaited.getResult());
    }

    @Test
    void consumedTaskNotificationIsDropped() throws Exception {
        var commandQueue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(commandQueue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
        var handle = manager.submit("done-2", () -> "done", null);
        handle.future().get(5, TimeUnit.SECONDS);

        manager.markConsumed("done-2");

        assertTrue(commandQueue.drainSameMode().isEmpty());
    }

    @Test
    void readingTaskOutputConsumesTerminalTask() throws Exception {
        var commandQueue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(commandQueue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
        var handle = manager.submit("done-3", () -> "done", null);
        handle.future().get(5, TimeUnit.SECONDS);

        manager.markOutputRead("memory://done-3");

        assertTrue(manager.isConsumed("done-3"));
        assertTrue(commandQueue.drainSameMode().isEmpty());
    }

    @Test
    void notifyFalseSkipsQueueNotificationButKeepsResult() throws Exception {
        var commandQueue = new SessionCommandQueue();
        var manager = new BackgroundTaskManager(commandQueue, taskId -> new InMemorySink(taskId), "test-session", event -> { });
        var handle = manager.submit("quiet-1", () -> "done", null, null, false);
        handle.future().get(5, TimeUnit.SECONDS);

        var result = manager.pollTask("quiet-1");

        assertTrue(result.isCompleted());
        assertEquals("done", result.getResult());
        assertTrue(commandQueue.drainSameMode().isEmpty());
    }

    @Test
    void awaitReturnsPendingWhenDeadlineExpires() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var manager = new BackgroundTaskManager(new SessionCommandQueue(), taskId -> new InMemorySink(taskId), "test-session", event -> { }, executor);
            var started = new CountDownLatch(1);
            var gate = new CountDownLatch(1);
            manager.submit("slow", () -> {
                started.countDown();
                awaitQuietly(gate);
                return "done";
            }, null);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            var result = manager.awaitTask("slow", 100);

            assertTrue(result.isPending());
            gate.countDown();
        }
    }

    @Test
    void runningTasksAreVisibleUntilTerminal() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var manager = new BackgroundTaskManager(new SessionCommandQueue(), taskId -> new InMemorySink(taskId), "test-session", event -> { }, executor);
            var started = new CountDownLatch(1);
            var gate = new CountDownLatch(1);
            manager.submit("slow", () -> {
                started.countDown();
                awaitQuietly(gate);
                return "done";
            }, null);
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertTrue(manager.hasRunningTasks());
            assertEquals(1, manager.runningTaskCount());

            gate.countDown();
            manager.awaitTask("slow", 5000);

            assertFalse(manager.hasRunningTasks());
            assertEquals(0, manager.runningTaskCount());
        }
    }

    private static final class InMemorySink implements SubagentOutputSink {
        private final String taskId;

        private InMemorySink(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public void write(String content) {
        }

        @Override
        public String getReference() {
            return "memory://" + taskId;
        }

        @Override
        public void close() {
        }
    }

    private static final class CapturingSink implements SubagentOutputSink {
        private final String taskId;
        private String content;

        private CapturingSink(String taskId) {
            this.taskId = taskId;
        }

        @Override
        public void write(String content) {
            this.content = content;
        }

        @Override
        public String getReference() {
            return "memory://" + taskId;
        }

        @Override
        public void close() {
        }
    }
}
