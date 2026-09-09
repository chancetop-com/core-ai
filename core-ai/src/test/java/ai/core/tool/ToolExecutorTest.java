package ai.core.tool;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;
import ai.core.persistence.PersistenceProvider;
import ai.core.sandbox.Sandbox;
import ai.core.session.SessionStreamingCallback;
import ai.core.sandbox.SandboxFile;
import ai.core.tool.tools.AsyncTaskOutputTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.tool.tools.WriteTodosTool;
import ai.core.sandbox.SandboxStatus;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.RecordingSpanProcessor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {
    private static final AttributeKey<Boolean> TOOL_IS_SUB_AGENT = AttributeKey.booleanKey("tool.is_sub_agent");

    private static ToolCall tool(String toolName, boolean isSubAgent) {
        return new FakeToolCall(toolName, isSubAgent);
    }

    private static Sandbox fakeSandbox(String interceptedToolName) {
        return new FakeSandbox(interceptedToolName);
    }

    @Test
    void associatesStreamingOutputWithExecutingToolCall() {
        var callId = new AtomicReference<String>();
        var tool = new FakeToolCall("run_bash_command", false) {
            @Override
            public ToolCallResult execute(String arguments, ExecutionContext context) {
                context.getStreamingCallback().onOutput("bash", null, "done");
                return ToolCallResult.completed("ok");
            }
        };
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var context = ExecutionContext.empty();
        context.setStreamingCallback(new SessionStreamingCallback("session_1", event -> {
            if (event instanceof EnvironmentOutputChunkEvent output) {
                callId.set(output.callId);
            }
        }, context));

        executor.execute(tool, FunctionCall.of("call_1", "function", "run_bash_command", "{}"), context);

        assertEquals("call_1", callId.get());
    }

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
    void ignoresMalformedWriteTodosArguments() {
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var tool = WriteTodosTool.self();
        var functionCall = FunctionCall.of("call_1", "function", WriteTodosTool.WT_TOOL_NAME,
                "{\"todos\":[{\"content\":\"update\" \"status\":\"in_progress\"}]}");

        ToolCallResult result = executor.execute(tool, functionCall, ExecutionContext.empty());

        assertTrue(result.isCompleted());
        assertEquals("The todo update was ignored because its arguments were not valid JSON. Continue with the current task.", result.getResult());
    }

    @Test
    void returnsFailureForMalformedToolArguments() {
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var functionCall = FunctionCall.of("call_1", "function", "read_file", "{\"path\": \"missing comma\" \"offset\": 1}");

        ToolCallResult result = executor.execute(tool("read_file", false), functionCall, ExecutionContext.empty());

        assertTrue(result.isFailed());
        assertEquals("Tool arguments are not valid JSON. Fix the arguments and retry this tool call.", result.getResult());
    }

    @Test
    void recommendsSectionedWritesForMalformedWriteFileArguments() {
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var functionCall = FunctionCall.of("call_1", "function", "write_file", "{\"file_path\": \"design.md\" \"content\": \"long code block\"}");

        ToolCallResult result = executor.execute(WriteFileTool.builder().build(), functionCall, ExecutionContext.empty());

        assertTrue(result.isFailed());
        assertEquals("write_file arguments are not valid JSON. The content is likely too large or contains heavily escaped code. "
            + "Write the file skeleton first, then use edit_file to append one chapter or logical section at a time.", result.getResult());
    }

    @Test
    void returnsFailureForMalformedConcurrentToolArguments() {
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var functionCall = FunctionCall.of("call_1", "function", "read_file", "{\"path\": \"missing comma\" \"offset\": 1}");

        ToolCallResult result = executor.executeWithoutLifecycle(tool("read_file", false), functionCall, ExecutionContext.empty());

        assertTrue(result.isFailed());
        assertEquals("Tool arguments are not valid JSON. Fix the arguments and retry this tool call.", result.getResult());
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

    @Test
    void forwardsLlmUsageOfToolToConsumer() {
        var capturedModel = new AtomicReference<String>();
        var capturedUsage = new AtomicReference<Usage>();
        var executor = new ToolExecutor(List.of(), null, status -> { },
                (model, usage) -> {
                    capturedModel.set(model);
                    capturedUsage.set(usage);
                }, () -> null);
        var tool = new FakeToolCall("caption_image", false) {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("a caption")
                        .withLlmUsage("gpt-4o", new Usage(120, 30, 150));
            }
        };
        var functionCall = FunctionCall.of("call_1", "function", "caption_image", "{}");

        var result = executor.execute(tool, functionCall, ExecutionContext.empty());

        assertEquals("gpt-4o", capturedModel.get());
        assertEquals(120, capturedUsage.get().getPromptTokens());
        assertEquals(30, capturedUsage.get().getCompletionTokens());
        assertEquals("gpt-4o", result.getLlmModel());
        assertTrue(result.getLlmCostUsd() > 0);
    }

    @Test
    void doesNotForwardLlmUsageWhenToolHasNone() {
        var capturedModel = new AtomicReference<String>();
        var executor = new ToolExecutor(List.of(), null, status -> { },
                (model, usage) -> capturedModel.set(model), () -> null);
        var functionCall = FunctionCall.of("call_1", "function", "read_file", "{}");

        executor.execute(tool("read_file", false), functionCall, ExecutionContext.empty());

        assertNull(capturedModel.get());
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

    @Test
    void pendingRelayFromAsyncTaskOutputIsNotRegisteredAsATask() {
        var manager = new ToolCallAsyncTaskManager(new MapPersistenceProvider(new HashMap<>()));
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var context = ExecutionContext.builder().asyncTaskManager(manager).build();
        // relays the status of a task the manager does not track (e.g. one owned by AsyncToolTaskExecutor)
        var relay = new FakeToolCall(AsyncTaskOutputTool.TOOL_NAME, false) {
            @Override
            public ToolCallResult execute(String arguments, ExecutionContext ctx) {
                return ToolCallResult.pending("py-1", "Task is still running");
            }
        };
        manager.registerTool(relay);

        var result = executor.execute(relay, FunctionCall.of("call_1", "function", AsyncTaskOutputTool.TOOL_NAME, "{}"), context);

        assertTrue(result.isPending());
        // registering the relay would store py-1 under async_task_output, which cannot poll, and the next
        // poll would drop the task with "does not support polling"
        assertTrue(manager.loadTask("py-1").isEmpty());
    }

    @Test
    void pendingResultReusingAFinishedTaskIdIsRegisteredAgain() {
        var manager = new ToolCallAsyncTaskManager(new MapPersistenceProvider(new HashMap<>()));
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var tool = new FakeToolCall("render_batch", false) {
            @Override
            public ToolCallResult execute(String arguments, ExecutionContext ctx) {
                return ToolCallResult.pending("batch-1", "queued");
            }
        };
        manager.registerTool(tool);
        var previous = FunctionCall.of("call_0", "function", "render_batch", "{}");
        // the previous batch under the same id finished a while ago and is still retained
        manager.storeTask(new ToolCallAsyncTask("batch-1", tool, previous, ToolCallResult.completed("done")), "old-session");
        var context = ExecutionContext.builder().asyncTaskManager(manager).sessionId("new-session").build();

        var result = executor.execute(tool, FunctionCall.of("call_1", "function", "render_batch", "{}"), context);

        assertTrue(result.isPending());
        var stored = manager.loadTask("batch-1").orElseThrow();
        assertTrue(stored.isPending(), "the new batch must be tracked so it gets polled and announced");
        assertEquals(Optional.of("new-session"), manager.sessionIdOf("batch-1"));
    }

    @Test
    void sandboxFailuresBecomeFailedToolCallsNotDeadTurns() {
        var executor = new ToolExecutor(List.of(), null, status -> { }, () -> null);
        var context = ExecutionContext.empty();
        context.sandbox(new FakeSandbox("run_bash_command") {
            @Override
            public ToolCallResult execute(String toolName, String arguments, ExecutionContext ctx) {
                throw new RuntimeException("Docker API request failed: /networks/core-ai-sandbox");
            }
        });

        var result = executor.execute(tool("run_bash_command", false), FunctionCall.of("call_1", "function", "run_bash_command", "{}"), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().startsWith("SANDBOX_UNAVAILABLE"), result.getResult());
        assertTrue(result.getResult().contains("/networks/core-ai-sandbox"), result.getResult());
    }

    @SuppressWarnings("PMD.ConstructorCallsOverridableMethod")
    private static class FakeToolCall extends ToolCall {
        private final boolean subAgent;

        FakeToolCall(String toolName, boolean isSubAgent) {
            setName(toolName);
            setDescription("test tool");
            setParameters(List.of());
            subAgent = isSubAgent;
        }

        @Override
        public boolean isSubAgent() {
            return subAgent;
        }

        @Override
        public ToolCallResult execute(String arguments) {
            return ToolCallResult.completed("ok");
        }
    }

    private static class FakeSandbox implements Sandbox {
        private final String interceptedToolName;

        FakeSandbox(String interceptedToolName) {
            this.interceptedToolName = interceptedToolName;
        }

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
    }

    static class MapPersistenceProvider implements PersistenceProvider {
        private final Map<String, String> store;

        MapPersistenceProvider(Map<String, String> store) {
            this.store = store;
        }

        @Override
        public void save(String id, String content) {
            store.put(id, content);
        }

        @Override
        public void clear() {
            store.clear();
        }

        @Override
        public void delete(List<String> ids) {
            ids.forEach(store::remove);
        }

        @Override
        public Optional<String> load(String id) {
            return Optional.ofNullable(store.get(id));
        }
    }
}
