package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.CancellationToken;
import ai.core.agent.ExecutionContext;
import ai.core.agent.SubAgentConfig;
import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.agent.profile.AgentProfileProvider;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.AsyncTaskOutputTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.TaskTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the generic async task flow (TaskTool with run_in_background=true):
 * launch -> BackgroundTaskManager -> completion notification -> notification turn output.
 *
 * @author stephen
 */
class AsyncTaskFlowTest {

    private static CompletionResponse simpleResponse(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30)
        );
    }

    private static CompletionResponse taskToolCallResponse(String taskId) {
        String json = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","name":"assistant","tool_calls":[{"id":"call_task_1","type":"function","function":{"name":"task","arguments":"{\\"task_id\\":\\"%s\\",\\"description\\":\\"test task\\",\\"prompt\\":\\"do the work\\",\\"subagent_type\\":\\"test-agent\\",\\"run_in_background\\":true}"},"index":null}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}""".formatted(taskId);
        return ai.core.utils.JsonUtil.fromJson(CompletionResponse.class, json);
    }

    private static CompletionResponse askUserToolCallResponse() {
        String json = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","name":"assistant","tool_calls":[{"id":"call_ask_1","type":"function","function":{"name":"ask_user","arguments":"{\\"question\\":\\"please confirm?\\"}"},"index":null}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                """;
        return ai.core.utils.JsonUtil.fromJson(CompletionResponse.class, json);
    }

    private static CompletionResponse asyncTaskOutputCancelCallResponse(String taskId) {
        String json = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","name":"assistant","tool_calls":[{"id":"call_cancel_1","type":"function","function":{"name":"async_task_output","arguments":"{\\"action\\":\\"cancel\\",\\"task_id\\":\\"%s\\"}"},"index":null}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}""".formatted(taskId);
        return ai.core.utils.JsonUtil.fromJson(CompletionResponse.class, json);
    }

    private static CompletionResponse shellToolCallResponse() {
        String json = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"","name":"assistant","tool_calls":[{"id":"call_shell_1","type":"function","function":{"name":"run_bash_command","arguments":"{\\"command\\":\\"ssh -L 16379:localhost:6379 host\\"}"},"index":null}]}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}
                """;
        return ai.core.utils.JsonUtil.fromJson(CompletionResponse.class, json);
    }

    /**
     * Builds the agent like the CLI does. The subagent gets its own LLM provider
     * (via SubAgentConfig) so the mock response queues never race: in reality the
     * main agent and background subagents share the same provider instance, but a
     * scripted mock cannot serve two concurrent consumers from one queue.
     */
    private static Agent buildAgent(MockLLMProvider provider, SubagentOutputSinkFactory sinkFactory,
                                    AgentProfile profile, List<ToolCall> extraTools,
                                    MockLLMProvider subProvider) {
        var registry = new ToolRegistry();
        registry.registerProvider(ListToolProvider.of(List.of(TaskTool.builder().build())));
        if (extraTools != null) {
            registry.registerProvider(ListToolProvider.of(extraTools));
        }

        var profileRegistry = new AgentProfileRegistry();
        profileRegistry.addProvider(new SingleProfileProvider(profile));

        var agent = Agent.builder()
                .llmProvider(provider)
                .maxTurn(20)
                .toolRegistry(registry)
                .build();

        var context = ExecutionContext.builder()
                .sessionId("async-test-session")
                .subagentOutputSinkFactory(sinkFactory)
                .build();
        context.setAgentProfileRegistry(profileRegistry);
        if (subProvider != null) {
            context.setSubAgentConfigs(Map.of("test-agent", new SubAgentConfig().llmProvider(subProvider)));
        }
        agent.setExecutionContext(context);
        return agent;
    }

    private static AgentProfile testAgentProfile(String systemPrompt, List<String> tools) {
        return new AgentProfile()
                .name("test-agent")
                .description("A test agent")
                .systemPrompt(systemPrompt)
                .tools(tools);
    }

    @Test
    void backgroundTaskCompletionFiresNotificationTurnWithOutput() throws Exception {
        var provider = new StreamingMockLLMProvider();
        provider.addResponse(taskToolCallResponse("bg-1"));          // main: launch task
        provider.addResponse(simpleResponse("Launched in background")); // main: final text
        provider.addResponse(simpleResponse("The background task bg-1 finished: background work result")); // notification turn

        var subProvider = new StreamingMockLLMProvider();
        subProvider.addResponse(simpleResponse("background work result")); // subagent: final text

        var agent = buildAgent(provider, new InMemorySinkFactory(), testAgentProfile("You are a test agent", null), null, subProvider);
        var session = new InProcessAgentSession("async-test-session", agent, true, new InMemoryToolPermissionStore());
        var listener = new RecordingListener();
        session.onEvent(listener);

        try {
            session.sendMessage("launch a background task");
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "launch turn must complete");
            // notification turn arrives async; wait for it
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (listener.turnCount() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }

            assertEquals(2, listener.turnCount(), "both the launch turn and the notification turn must complete");
            assertEquals("Launched in background", listener.turnOutputs.get(0));
            assertTrue(listener.turnOutputs.get(1).contains("The background task bg-1 finished"),
                    "notification turn output must be dispatched, actual: " + listener.turnOutputs);
            assertTrue(listener.text().contains("The background task bg-1 finished"),
                    "notification turn text chunks must be dispatched, actual: " + listener.text());
        } finally {
            session.close();
        }
    }

    @Test
    void subagentHasNoStreamingCallbackSoNoLiveOutputDuringBackgroundRun() throws Exception {
        var provider = new StreamingMockLLMProvider();
        provider.addResponse(taskToolCallResponse("bg-2"));
        provider.addResponse(simpleResponse("Launched in background"));
        provider.addResponse(simpleResponse("done"));

        var subProvider = new StreamingMockLLMProvider();
        subProvider.addResponse(simpleResponse("background work result"));

        var agent = buildAgent(provider, new InMemorySinkFactory(), testAgentProfile("You are a test agent", null), null, subProvider);
        var session = new InProcessAgentSession("async-test-session-2", agent, true, new InMemoryToolPermissionStore());
        var listener = new RecordingListener();
        session.onEvent(listener);

        try {
            session.sendMessage("launch a background task");
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "launch turn must complete");
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (listener.turnCount() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }

            // The main agent's launch-turn text IS streamed (streaming callback wired).
            assertTrue(listener.text().contains("Launched in background"));
            // The subagent's text response never reaches the listeners: its context carries
            // no streaming callback, so the CLI shows nothing while the background task runs.
            assertFalse(listener.text().contains("background work result"),
                    "subagent text must not stream without a streaming callback");
            // Only the final notification turn output is visible.
            assertTrue(listener.text().contains("done"));
        } finally {
            session.close();
        }
    }

    @Test
    void askUserIsRejectedInsideBackgroundSubagent() throws Exception {
        var provider = new StreamingMockLLMProvider();
        provider.addResponse(taskToolCallResponse("bg-3"));
        provider.addResponse(simpleResponse("Launched in background"));
        provider.addResponse(simpleResponse("The background task bg-3 finished: question for main agent: please confirm?"));

        // The CLI question handler would block reading the terminal - it must never be invoked.
        var handlerInvoked = new AtomicBoolean(false);
        var askUser = AskUserTool.builder()
                .questionHandler(q -> {
                    handlerInvoked.set(true);
                    return "answer";
                })
                .build();

        var subProvider = new StreamingMockLLMProvider();
        subProvider.addResponse(askUserToolCallResponse());  // subagent tries to ask the user
        subProvider.addResponse(simpleResponse("question for main agent: please confirm?")); // falls back to returning the question

        var agent = buildAgent(provider, new InMemorySinkFactory(),
                testAgentProfile("You are a test agent", List.of(AskUserTool.TOOL_NAME)), List.of(askUser), subProvider);
        var session = new InProcessAgentSession("async-test-session-3", agent, true, new InMemoryToolPermissionStore());
        var listener = new RecordingListener();
        session.onEvent(listener);

        try {
            session.sendMessage("launch a background task");
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "launch turn must complete");
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (listener.turnCount() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }

            // ask_user was rejected inside the subagent instead of blocking forever,
            // and the subagent completed normally with the question in its result.
            assertFalse(handlerInvoked.get(), "question handler must not be invoked inside a subagent");
            assertEquals(2, listener.turnCount(), "background task must complete instead of hanging on ask_user");
            assertEquals(1, listener.taskStatusEvents.size());
            assertEquals("completed", listener.taskStatusEvents.getFirst().status);
            assertEquals("bg-3", listener.taskStatusEvents.getFirst().taskId);
            assertTrue(listener.text().contains("question for main agent"),
                    "the subagent question must reach the notification turn, actual: " + listener.text());
        } finally {
            session.close();
        }
    }

    @Test
    void mainAgentCanCancelBackgroundTask() throws Exception {
        var provider = new StreamingMockLLMProvider();
        provider.addResponse(taskToolCallResponse("bg-cancel"));
        provider.addResponse(asyncTaskOutputCancelCallResponse("bg-cancel"));
        provider.addResponse(simpleResponse("stopped it"));
        provider.addResponse(simpleResponse("Background task bg-cancel was cancelled"));

        // Slow subagent so the cancel arrives while it is still running.
        var subProvider = new SlowStreamingMockLLMProvider(3000);
        subProvider.addResponse(simpleResponse("background work result"));

        var agent = buildAgent(provider, new InMemorySinkFactory(), testAgentProfile("You are a test agent", null),
                List.of(AsyncTaskOutputTool.builder().build()), subProvider);
        var session = new InProcessAgentSession("async-test-session-cancel", agent, true, new InMemoryToolPermissionStore());
        var listener = new RecordingListener();
        session.onEvent(listener);

        try {
            session.sendMessage("launch a background task");
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "launch turn must complete");
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (listener.turnCount() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }

            assertEquals(2, listener.turnCount(), "launch turn + cancellation notification turn must complete");
            assertEquals(1, listener.taskStatusEvents.size());
            assertEquals("cancelled", listener.taskStatusEvents.getFirst().status);
            assertEquals("bg-cancel", listener.taskStatusEvents.getFirst().taskId);
            assertTrue(listener.text().contains("Background task bg-cancel was cancelled"),
                    "the main agent must learn about the cancellation, actual: " + listener.text());
        } finally {
            session.close();
        }
    }

    @Test
    void sessionCloseDropsPendingTaskNotification() throws Exception {
        var provider = new StreamingMockLLMProvider();
        provider.addResponse(taskToolCallResponse("bg-4"));
        provider.addResponse(simpleResponse("Launched in background"));
        provider.addResponse(simpleResponse("should never be consumed"));

        // Slow subagent: guarantees the completion notification lands AFTER session.close().
        var subProvider = new SlowStreamingMockLLMProvider(500);
        subProvider.addResponse(simpleResponse("background work result"));

        var agent = buildAgent(provider, new InMemorySinkFactory(), testAgentProfile("You are a test agent", null), null, subProvider);
        var session = new InProcessAgentSession("async-test-session-4", agent, true, new InMemoryToolPermissionStore());
        var listener = new RecordingListener();
        session.onEvent(listener);

        // Like CLI one-shot prompt mode: the session is closed as soon as the launch turn ends.
        session.sendMessage("launch a background task");
        assertTrue(listener.firstTurn.await(5, TimeUnit.SECONDS), "launch turn must complete");
        session.close(); // TurnDriver shuts down; the completion notification is never processed

        // Give the background task time to finish and enqueue its notification.
        Thread.sleep(2000);
        assertEquals(1, listener.turnCount(),
                "notification turn must not run after session.close()");
        assertFalse(listener.text().contains("finished"));
    }

    @Test
    void taskToolResultCarriesLaunchNotificationForLlm() throws Exception {
        var provider = new StreamingMockLLMProvider();
        provider.addResponse(taskToolCallResponse("bg-5"));
        provider.addResponse(simpleResponse("Launched in background"));
        provider.addResponse(simpleResponse("done"));

        var subProvider = new StreamingMockLLMProvider();
        subProvider.addResponse(simpleResponse("background work result"));

        var agent = buildAgent(provider, new InMemorySinkFactory(), testAgentProfile("You are a test agent", null), null, subProvider);
        var session = new InProcessAgentSession("async-test-session-5", agent, true, new InMemoryToolPermissionStore());
        var listener = new RecordingListener();
        session.onEvent(listener);

        try {
            session.sendMessage("launch a background task");
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "launch turn must complete");

            var toolResults = listener.events.stream()
                    .filter(e -> e instanceof ToolResultEvent t && "task".equals(t.toolName))
                    .map(e -> (ToolResultEvent) e)
                    .toList();
            assertEquals(1, toolResults.size());
            assertEquals("async_launched", toolResults.getFirst().status);
            assertNotNull(toolResults.getFirst().result);
            assertTrue(toolResults.getFirst().result.contains("<task-notification>"),
                    "launch result must contain the task-notification XML, actual: " + toolResults.getFirst().result);
        } finally {
            session.close();
        }
    }

    /**
     * Reproduces the reported bug: when the background subagent's run ends with the
     * last message being a TOOL message (here the run is cancelled while the tool is
     * blocked), the output file is written with the tool's raw output ("process logs")
     * instead of the agent's final answer (Last.content).
     */
    @Test
    void cancelledDuringToolWritesToolOutputInsteadOfLastContent() throws Exception {
        var provider = new StreamingMockLLMProvider();
        provider.addResponse(taskToolCallResponse("bg-logs"));          // main: launch task
        provider.addResponse(simpleResponse("Launched in background")); // main: final text
        provider.addResponse(simpleResponse("done"));                   // main: notification turn

        var subProvider = new StreamingMockLLMProvider();
        var written = new CopyOnWriteArrayList<String>();
        var shellTool = new BlockingShellTool();
        subProvider.addResponse(shellToolCallResponse());               // subagent: call run_bash_command

        var agent = buildAgent(provider, new CapturingSinkFactory(written), testAgentProfile("You are a test agent", List.of(ShellCommandTool.TOOL_NAME)),
                List.of(shellTool), subProvider);
        var session = new InProcessAgentSession("async-test-session-logs", agent, true, new InMemoryToolPermissionStore());
        var listener = new RecordingListener();
        session.onEvent(listener);

        try {
            session.sendMessage("launch a background task");
            assertTrue(listener.firstTurn.await(10, TimeUnit.SECONDS), "launch turn must complete");
            assertTrue(shellTool.started.await(10, TimeUnit.SECONDS), "subagent tool must start");

            // Cancel the subagent's run while the tool is still executing; the tool then
            // completes and its result is the last message appended to the subagent.
            shellTool.subToken.cancel();
            shellTool.release.countDown();

            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (written.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(written.isEmpty(), "sink must be written");
            assertFalse(written.getLast().contains("Forwarding from"),
                    "the file must not contain the raw tool output (process logs), actual: " + written.getLast());
        } finally {
            session.close();
        }
    }

    /** Records events like the CLI listener does. */
    private static final class RecordingListener implements AgentEventListener {
        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        private final List<TaskStatusEvent> taskStatusEvents = new CopyOnWriteArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final List<String> turnOutputs = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstTurn = new CountDownLatch(1);

        @Override
        public void onTextChunk(TextChunkEvent event) {
            synchronized (text) {
                text.append(event.chunk);
            }
        }

        @Override
        public void onToolResult(ToolResultEvent event) {
            events.add(event);
        }

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            events.add(event);
            turnOutputs.add(event.output != null ? event.output : "");
            firstTurn.countDown();
        }

        @Override
        public void onTaskStatus(TaskStatusEvent event) {
            events.add(event);
            taskStatusEvents.add(event);
        }

        @Override
        public void onError(ErrorEvent event) {
            events.add(event);
        }

        public String text() {
            synchronized (text) {
                return text.toString();
            }
        }

        public int turnCount() {
            return (int) events.stream().filter(e -> e instanceof TurnCompleteEvent).count();
        }
    }

    /**
     * MockLLMProvider variant that streams the response text through the callback
     * like a real provider, so streaming-connected listeners receive chunks.
     */
    private static class StreamingMockLLMProvider extends MockLLMProvider {
        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            var response = super.doCompletionStream(request, callback);
            if (callback != null) {
                var text = response.choices.getFirst().message.content;
                if (text != null && !text.isEmpty()) {
                    callback.onChunk(text);
                }
            }
            return response;
        }
    }

    /** StreamingMockLLMProvider that delays each completion, simulating a slow subagent. */
    private static final class SlowStreamingMockLLMProvider extends StreamingMockLLMProvider {
        private final long delayMs;

        private SlowStreamingMockLLMProvider(long delayMs) {
            this.delayMs = delayMs;
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.doCompletionStream(request, callback);
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

    private static final class InMemorySinkFactory implements SubagentOutputSinkFactory {
        @Override
        public SubagentOutputSink create(String taskId) {
            return new InMemorySink(taskId);
        }
    }

    /** Sink that records the content written by the background task, like FileSubagentOutputSink. */
    private static final class CapturingSink implements SubagentOutputSink {
        private final List<String> written;

        private CapturingSink(List<String> written) {
            this.written = written;
        }

        @Override
        public void write(String content) {
            written.add(content);
        }

        @Override
        public String getReference() {
            return "capture://";
        }

        @Override
        public void close() {
        }
    }

    private static final class CapturingSinkFactory implements SubagentOutputSinkFactory {
        private final List<String> written;

        private CapturingSinkFactory(List<String> written) {
            this.written = written;
        }

        @Override
        public SubagentOutputSink create(String taskId) {
            return new CapturingSink(written);
        }
    }

    /**
     * Simulates the run_bash_command tool: blocks until released, then returns a shell
     * output snippet that looks like process logs. Exposes the subagent's cancellation
     * token so the test can interrupt the run while the tool is still executing.
     */
    private static final class BlockingShellTool extends ToolCall {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile CancellationToken subToken;

        BlockingShellTool() {
            setName("run_bash_command");
            setParameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "command", "The bash command").required()
            ));
        }

        @Override
        public ToolCallResult execute(String arguments, ExecutionContext context) {
            subToken = context.getCancellationToken();
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolCallResult.completed("Forwarding from 127.0.0.1:16379 -> 6379");
        }

        @Override
        public ToolCallResult execute(String arguments) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SingleProfileProvider implements AgentProfileProvider {
        private final AgentProfile profile;

        private SingleProfileProvider(AgentProfile profile) {
            this.profile = profile;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public List<AgentProfile> provide() {
            return List.of(profile);
        }
    }
}
