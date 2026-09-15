package ai.core.tool;

import ai.core.agent.CancelReason;
import ai.core.agent.CancellationToken;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxBinding;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxStatus;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.RecordingSpanProcessor;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sandbox-intercepted tools bypass executeWithTimeout, so their failure and cancellation
 * handling lives on a separate path; these tests pin that path down.
 */
class ToolExecutorSandboxBoundsTest {
    private static final FunctionCall BASH_CALL = FunctionCall.of("call_1", "function", "run_bash_command", "{}");
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void failedSandboxResultMarksToolSpanAsError() {
        var spans = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(spans).build();
        try {
            var tracer = new AgentTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
            var executor = new ToolExecutor(List.of(), tracer, status -> { }, () -> null);
            var context = ExecutionContext.builder().sandbox(new BlockingSandbox(null,
                ToolCallResult.failed("Sandbox execution failed: http request failed, error=timeout"))).build();

            executor.execute(new StubTool(), BASH_CALL, context);

            var span = spans.find("run_bash_command").orElseThrow();
            assertEquals(StatusCode.ERROR, span.getStatus().getStatusCode());
            assertTrue(span.getStatus().getDescription().contains("error=timeout"), span.getStatus().getDescription());
        } finally {
            tracerProvider.shutdown();
        }
    }

    @Test
    void cancellingTheRunReleasesACallerBlockedOnTheSandbox() throws InterruptedException {
        var release = new CountDownLatch(1);
        var token = CancellationToken.create();
        var context = ExecutionContext.builder().cancellationToken(token)
            .sandbox(new BlockingSandbox(release, ToolCallResult.completed("late"))).build();
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        scheduler.schedule(() -> token.cancel(CancelReason.TIMEOUT), 200, TimeUnit.MILLISECONDS);

        var start = System.currentTimeMillis();
        ToolCallResult result;
        try {
            result = executor.execute(new StubTool(), BASH_CALL, context);
        } finally {
            release.countDown();
        }

        assertTrue(System.currentTimeMillis() - start < 5_000, "caller stayed blocked on the sandbox after cancel");
        assertTrue(result.isFailed(), result.getResult());
        assertTrue(result.getResult().contains("cancelled"), result.getResult());
    }

    private static final class StubTool extends ToolCall {
        StubTool() {
            setName("run_bash_command");
            setDescription("test tool");
            setParameters(List.of());
        }

        @Override
        public ToolCallResult execute(String arguments) {
            return ToolCallResult.completed("ok");
        }
    }

    private static final class BlockingSandbox implements Sandbox {
        private final CountDownLatch release;
        private final ToolCallResult result;

        BlockingSandbox(CountDownLatch release, ToolCallResult result) {
            this.release = release;
            this.result = result;
        }

        @Override
        public boolean shouldIntercept(String toolName) {
            return true;
        }

        @Override
        public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
            if (release != null) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return result;
        }

        @Override
        public SandboxStatus getStatus() {
            return SandboxStatus.READY;
        }

        @Override
        public String getId() {
            return "sandbox-1";
        }

        @Override
        public String hostname() {
            return "sandbox-1";
        }

        @Override
        public void materializeSkill(String name, String version, byte[] tarBytes) {
        }

        @Override
        public SandboxFile downloadFile(String path) {
            return null;
        }

        @Override
        public void uploadFile(String path, byte[] content) {
        }

        @Override
        public String ip() {
            return "127.0.0.1";
        }

        @Override
        public int port() {
            return 8080;
        }

        @Override
        public String image() {
            return "test";
        }

        @Override
        public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
            return id;
        }

        @Override
        public void stopMcpServer(String id) {
        }

        @Override
        public String getMcpEndpoint() {
            return null;
        }

        @Override
        public void bind(SandboxBinding binding) {
        }

        @Override
        public void unbind() {
        }

        @Override
        public void close() {
        }
    }
}
