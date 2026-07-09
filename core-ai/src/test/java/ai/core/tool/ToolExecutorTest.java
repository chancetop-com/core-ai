package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxFile;
import ai.core.sandbox.SandboxStatus;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.RecordingSpanProcessor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolExecutorTest {
    private static final AttributeKey<Boolean> TOOL_IS_SUB_AGENT = AttributeKey.booleanKey("tool.is_sub_agent");

    @Test
    void marksSubAgentToolSpanWithIsSubAgentAttribute() {
        var spans = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(spans).build();
        try {
            var tracer = new AgentTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
            var executor = new ToolExecutor(List.of(), tracer, status -> { }, () -> null);
            var tool = tool("delegate_to_researcher", true);
            var functionCall = FunctionCall.of("call_1", "function", "delegate_to_researcher", "{}");

            executor.execute(tool, functionCall, ExecutionContext.empty());

            var span = spans.find("delegate_to_researcher").orElseThrow();
            assertEquals(Boolean.TRUE, span.getAttributes().get(TOOL_IS_SUB_AGENT));
        } finally {
            tracerProvider.shutdown();
        }
    }

    @Test
    void omitsIsSubAgentAttributeForRegularTools() {
        var spans = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(spans).build();
        try {
            var tracer = new AgentTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
            var executor = new ToolExecutor(List.of(), tracer, status -> { }, () -> null);
            var tool = tool("grep_file", false);
            var functionCall = FunctionCall.of("call_1", "function", "grep_file", "{}");

            executor.execute(tool, functionCall, ExecutionContext.empty());

            var span = spans.find("grep_file").orElseThrow();
            assertNull(span.getAttributes().get(TOOL_IS_SUB_AGENT));
        } finally {
            tracerProvider.shutdown();
        }
    }

    // Sandbox-intercepted tools run through a separate traceToolSpan() call site than the
    // normal execution path above; cover it separately so the two call sites can't drift apart.
    @Test
    void marksSubAgentToolSpanWithIsSubAgentAttributeThroughSandbox() {
        var spans = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(spans).build();
        try {
            var tracer = new AgentTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
            var executor = new ToolExecutor(List.of(), tracer, status -> { }, () -> null);
            var tool = tool("delegate_to_researcher", true);
            var functionCall = FunctionCall.of("call_1", "function", "delegate_to_researcher", "{}");
            var context = ExecutionContext.builder().sandbox(fakeSandbox("delegate_to_researcher")).build();

            executor.execute(tool, functionCall, context);

            var span = spans.find("delegate_to_researcher").orElseThrow();
            assertEquals(Boolean.TRUE, span.getAttributes().get(TOOL_IS_SUB_AGENT));
        } finally {
            tracerProvider.shutdown();
        }
    }

    // Parameter is named toolName, not name: ToolCall.name is a package-private field, and since this
    // test lives in the same package (ai.core.tool), a parameter called "name" would be shadowed by
    // that inherited field inside the anonymous subclass body below instead of binding to this method's argument.
    private static ToolCall tool(String toolName, boolean isSubAgent) {
        return new ToolCall() {
            {
                setName(toolName);
                setDescription("test tool");
                setParameters(List.of());
            }

            @Override
            public boolean isSubAgent() {
                return isSubAgent;
            }

            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("ok");
            }
        };
    }

    private static Sandbox fakeSandbox(String interceptedToolName) {
        return new Sandbox() {
            @Override
            public boolean shouldIntercept(String toolName) {
                return interceptedToolName.equals(toolName);
            }

            @Override
            public ToolCallResult execute(String toolName, String arguments, ExecutionContext context) {
                return ToolCallResult.completed("sandboxed: " + toolName);
            }

            @Override
            public SandboxStatus getStatus() {
                return SandboxStatus.READY;
            }

            @Override
            public String getId() {
                return "test-sandbox";
            }

            @Override
            public String hostname() {
                return "test-sandbox";
            }

            @Override
            public void materializeSkill(String name, String version, byte[] tarBytes) {
            }

            @Override
            public SandboxFile downloadFile(String path) {
                throw new UnsupportedOperationException();
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
                return "test-image";
            }

            @Override
            public String startMcpServer(String id, String command, List<String> args, Map<String, String> env, int timeoutSeconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void stopMcpServer(String id) {
            }

            @Override
            public String getMcpEndpoint() {
                return "http://127.0.0.1:8080/mcp";
            }

            @Override
            public void close() {
            }
        };
    }
}
