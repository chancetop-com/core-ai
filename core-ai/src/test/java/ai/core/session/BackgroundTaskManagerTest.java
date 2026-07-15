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

class BackgroundTaskManagerTest {

    private static String executeTask(Tracer tracer) {
        var agentSpan = tracer.spanBuilder("background-agent").startSpan();
        try {
            return "done";
        } finally {
            agentSpan.end();
        }
    }

    private static void submitAndWait(Tracer tracer, BackgroundTaskManager manager, Span parentSpan) throws Exception {
        try (var scope = parentSpan.makeCurrent()) {
            var handle = manager.submit("deep-research-1", () -> executeTask(tracer), null);
            handle.future().get(5, TimeUnit.SECONDS);
        } finally {
            parentSpan.end();
        }
    }

    @Test
    void backgroundTaskKeepsSubmittingTraceContext() throws Exception {
        var spans = new RecordingSpanProcessor();
        try (var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spans)
                .build()) {
            var openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .build();
            var tracer = openTelemetry.getTracer("test");
            var commandQueue = new SessionCommandQueue();
            var manager = new BackgroundTaskManager(commandQueue, taskId -> new InMemorySink(taskId));
            var parentSpan = tracer.spanBuilder("task-tool").startSpan();

            submitAndWait(tracer, manager, parentSpan);

            var backgroundAgent = spans.find("background-agent").orElseThrow();
            assertEquals(parentSpan.getSpanContext().getTraceId(), backgroundAgent.getTraceId());
            assertEquals(parentSpan.getSpanContext().getSpanId(), backgroundAgent.getParentSpanId());
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
}
