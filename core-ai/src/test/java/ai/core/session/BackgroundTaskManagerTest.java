package ai.core.session;

import ai.core.telemetry.RecordingSpanProcessor;
import ai.core.tool.subagent.SubagentOutputSink;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackgroundTaskManagerTest {

    @Test
    @SuppressWarnings({"checkstyle:NestedTryDepth", "PMD.UseTryWithResources"})
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
            var manager = new BackgroundTaskManager(commandQueue, taskId -> new InMemorySink(taskId));
            var parentSpan = tracer.spanBuilder("task-tool").startSpan();

            var scope = parentSpan.makeCurrent();
            try {
                var handle = manager.submit("deep-research-1", () -> {
                    var agentSpan = tracer.spanBuilder("background-agent").startSpan();
                    try {
                        return "done";
                    } finally {
                        agentSpan.end();
                    }
                }, null);
                handle.future().get(5, TimeUnit.SECONDS);
            } finally {
                scope.close();
                parentSpan.end();
            }

            var backgroundAgent = spans.find("background-agent").orElseThrow();
            assertEquals(parentSpan.getSpanContext().getTraceId(), backgroundAgent.getTraceId());
            assertEquals(parentSpan.getSpanContext().getSpanId(), backgroundAgent.getParentSpanId());
        } finally {
            tracerProvider.shutdown();
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
