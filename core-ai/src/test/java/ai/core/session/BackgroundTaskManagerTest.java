package ai.core.session;

import ai.core.telemetry.RecordingSpanProcessor;
import ai.core.tool.subagent.SubagentOutputSink;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

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
