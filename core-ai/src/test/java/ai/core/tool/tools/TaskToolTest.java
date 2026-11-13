package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.agent.SubAgentConfig;
import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileProvider;
import ai.core.agent.profile.AgentProfileRegistry;
import ai.core.agent.subagent.SubagentModelResolver;
import ai.core.agent.subagent.SubagentModelUnavailableException;
import ai.core.agent.subagent.SubagentThreadState;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.persistence.PersistenceProvider;
import ai.core.session.BackgroundTaskManager;
import ai.core.session.SessionCommandQueue;
import ai.core.tool.registry.ListToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.subagent.SubagentOutputSink;
import ai.core.tool.subagent.SubagentOutputSinkFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The task tool spawns local subagents and can continue an existing one by task_id; the continuation
 * rides the same agent instance with its earlier messages, and an unknown id starts a fresh thread
 * that says so in its result.
 *
 * @author stephen
 */
class TaskToolTest {

    private static CompletionResponse text(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30)
        );
    }

    private static AgentProfile exploreProfile() {
        return new AgentProfile()
                .name("explore")
                .description("Explores the codebase")
                .systemPrompt("You are an explorer")
                .source("test");
    }

    private static ExecutionContext context(RecordingMockLLMProvider subProvider, PersistenceProvider persistence) {
        var toolRegistry = new ToolRegistry();
        toolRegistry.registerProvider(ListToolProvider.of(List.of()));
        var profileRegistry = new AgentProfileRegistry();
        profileRegistry.addProvider(new SingleProfileProvider(exploreProfile()));

        var context = ExecutionContext.builder()
                .sessionId("task-tool-test")
                .persistenceProvider(persistence)
                .build();
        context.setLlmProvider(new MockLLMProvider());
        context.setToolRegistry(toolRegistry);
        context.setAgentProfileRegistry(profileRegistry);
        context.setSubAgentConfigs(Map.of("explore", new SubAgentConfig().llmProvider(subProvider)));
        return context;
    }

    @Test
    void taskIsTaggedAsSubagent() {
        var tool = TaskTool.builder().build();

        assertEquals("subagent", tool.getSourceType());
    }

    @Test
    void continueWithSameTaskIdKeepsContext() {
        var subProvider = new RecordingMockLLMProvider();
        subProvider.addResponse(text("first result"));
        subProvider.addResponse(text("second result"));
        var context = context(subProvider, new MapPersistence());
        var tool = TaskTool.builder().build();

        var first = tool.execute("{\"description\":\"find stuff\",\"prompt\":\"first question\",\"subagent_type\":\"explore\",\"task_id\":\"sa-test01\"}", context);
        assertTrue(first.isCompleted(), first.getResult());
        assertTrue(first.getResult().contains("task_id=sa-test01"), first.getResult());
        assertTrue(first.getResult().contains("runs=1"), first.getResult());
        var thread = context.getSubagentThreads().find("sa-test01").orElseThrow();
        var firstAgent = thread.agent();

        var second = tool.execute("{\"description\":\"find stuff\",\"prompt\":\"second question\",\"task_id\":\"sa-test01\"}", context);

        assertTrue(second.isCompleted(), second.getResult());
        assertTrue(second.getResult().contains("runs=2"), second.getResult());
        assertEquals("second result", second.getResult().substring(0, "second result".length()));
        // the same agent instance ran the follow-up, with the first conversation still in its messages
        assertSame(firstAgent, context.getSubagentThreads().find("sa-test01").orElseThrow().agent());
        var secondRequestTexts = subProvider.requests.get(1).messages.stream()
                .map(Message::getTextContent)
                .filter(Objects::nonNull)
                .toList();
        assertTrue(secondRequestTexts.stream().anyMatch(t -> t.contains("first question")),
                "the continuation must carry the first prompt, actual: " + secondRequestTexts);
        assertTrue(secondRequestTexts.stream().anyMatch(t -> t.contains("second question")),
                "the continuation must carry the new prompt, actual: " + secondRequestTexts);
        assertTrue(secondRequestTexts.stream().anyMatch(t -> t.contains("first result")),
                "the continuation must carry the first answer, actual: " + secondRequestTexts);
    }

    @Test
    void unknownTaskIdStartsFreshThreadAndSaysSo() {
        var subProvider = new RecordingMockLLMProvider();
        subProvider.addResponse(text("fresh result"));
        var context = context(subProvider, new MapPersistence());
        var tool = TaskTool.builder().build();

        var result = tool.execute("{\"description\":\"work\",\"prompt\":\"do it\",\"subagent_type\":\"explore\",\"task_id\":\"sa-madeup\"}", context);

        assertTrue(result.isCompleted(), result.getResult());
        assertTrue(result.getResult().contains("no earlier context found for this id"), result.getResult());
        assertTrue(result.getResult().contains("runs=1"), result.getResult());
    }

    @Test
    void continuationWithWrongTypeFails() {
        var subProvider = new RecordingMockLLMProvider();
        subProvider.addResponse(text("first"));
        var context = context(subProvider, new MapPersistence());
        var tool = TaskTool.builder().build();
        tool.execute("{\"description\":\"work\",\"prompt\":\"do it\",\"subagent_type\":\"explore\",\"task_id\":\"sa-type1\"}", context);

        var result = tool.execute("{\"description\":\"work\",\"prompt\":\"more\",\"subagent_type\":\"other\",\"task_id\":\"sa-type1\"}", context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("belongs to subagent type 'explore'"), result.getResult());
    }

    @Test
    void missingTypeOnNewSubagentFails() {
        var context = context(new RecordingMockLLMProvider(), new MapPersistence());
        var result = TaskTool.builder().build()
                .execute("{\"description\":\"work\",\"prompt\":\"do it\"}", context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("subagent_type is required"), result.getResult());
    }

    @Test
    void continuingARunningBackgroundSubagentIsRejected() {
        var subProvider = new BlockingMockLLMProvider();
        subProvider.addResponse(text("background result"));
        var context = context(subProvider, new MapPersistence());
        var taskManager = new BackgroundTaskManager(new SessionCommandQueue(), new InMemorySinkFactory(), "task-tool-test", event -> { });
        context.setTaskManager(taskManager);
        var tool = TaskTool.builder().build();

        var launched = tool.execute("{\"description\":\"work\",\"prompt\":\"do it\",\"subagent_type\":\"explore\",\"task_id\":\"sa-bg01\",\"run_in_background\":true}", context);
        assertTrue(launched.isAsyncLaunched(), launched.getResult());
        assertTrue(launched.getResult().contains("<task-notification>"), launched.getResult());

        var busy = tool.execute("{\"description\":\"work\",\"prompt\":\"more\",\"task_id\":\"sa-bg01\"}", context);
        assertTrue(busy.isFailed());
        assertTrue(busy.getResult().contains("still running"), busy.getResult());

        // let the background run finish so the test leaves no live thread behind
        subProvider.release.countDown();
        taskManager.cancel("sa-bg01");
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (context.getSubagentThreads().find("sa-bg01").orElseThrow().isRunning() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertFalse(context.getSubagentThreads().find("sa-bg01").orElseThrow().isRunning());
    }

    @Test
    void descriptionListsActiveSubagents() {
        var subProvider = new RecordingMockLLMProvider();
        subProvider.addResponse(text("first result"));
        var context = context(subProvider, new MapPersistence());
        var tool = TaskTool.builder().build();

        var before = tool.toTool(context).function.description;
        assertFalse(before.contains("Active subagents"), "no list before the first subagent runs");

        tool.execute("{\"description\":\"find stuff\",\"prompt\":\"first question\",\"subagent_type\":\"explore\",\"task_id\":\"sa-list01\"}", context);

        var after = tool.toTool(context).function.description;
        assertTrue(after.contains("Active subagents in this session"), after);
        assertTrue(after.contains("sa-list01"), after);
        assertTrue(after.contains("(explore)"), after);
        assertTrue(after.contains("1 run,"), after);
    }

    @Test
    void threadsPersistAndReloadDormant() {
        var persistence = new MapPersistence();
        var subProvider = new RecordingMockLLMProvider();
        subProvider.addResponse(text("first result"));
        subProvider.addResponse(text("second result"));
        var context = context(subProvider, persistence);
        var tool = TaskTool.builder().build();
        tool.execute("{\"description\":\"find stuff\",\"prompt\":\"first question\",\"subagent_type\":\"explore\",\"task_id\":\"sa-p01\"}", context);

        // a fresh registry over the same store = the session after a restart
        var reloaded = new ai.core.agent.subagent.SubagentThreadRegistry("task-tool-test", persistence);
        var thread = reloaded.find("sa-p01").orElseThrow();
        assertEquals(SubagentThreadState.DORMANT, thread.state());
        assertEquals(1, thread.runCount());

        var restoredContext = context(subProvider, persistence);
        var continued = TaskTool.builder().build()
                .execute("{\"description\":\"find stuff\",\"prompt\":\"second question\",\"task_id\":\"sa-p01\"}", restoredContext);

        assertTrue(continued.isCompleted(), continued.getResult());
        assertTrue(continued.getResult().contains("runs=2"), continued.getResult());
        var requestTexts = subProvider.requests.get(1).messages.stream()
                .map(Message::getTextContent)
                .filter(Objects::nonNull)
                .toList();
        assertTrue(requestTexts.stream().anyMatch(t -> t.contains("first question")),
                "the reloaded thread must carry its earlier context, actual: " + requestTexts);
    }

    @Test
    void explicitModelUsesTheResolvedProvider() {
        var subProvider = new RecordingMockLLMProvider();
        subProvider.addResponse(text("m2 result"));
        var context = context(subProvider, new MapPersistence());
        var resolver = new FixedModelResolver().put("m2", subProvider, "p2");
        context.setSubagentModelResolver(resolver);

        var result = TaskTool.builder().build()
                .execute("{\"description\":\"work\",\"prompt\":\"do it\",\"subagent_type\":\"explore\",\"model\":\"m2\"}", context);

        assertTrue(result.isCompleted(), result.getResult());
        assertEquals("m2 result", result.getResult().substring(0, "m2 result".length()));
        assertEquals(1, subProvider.requests.size());
        assertEquals("m2", subProvider.requests.getFirst().model);
        var thread = context.getSubagentThreads().list().getFirst();
        assertEquals("m2", thread.model());
        assertEquals("p2", thread.provider());
        assertTrue(result.getResult().contains("model=m2"), result.getResult());
    }

    @Test
    void unknownExplicitModelFailsBeforeCreatingAThread() {
        var context = context(new RecordingMockLLMProvider(), new MapPersistence());
        context.setSubagentModelResolver(new FixedModelResolver());

        var result = TaskTool.builder().build()
                .execute("{\"description\":\"work\",\"prompt\":\"do it\",\"subagent_type\":\"explore\",\"model\":\"missing\"}", context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("[SUBAGENT_MODEL_UNKNOWN]"), result.getResult());
        assertTrue(context.getSubagentThreads().list().isEmpty(), "a failed model selection must not register a thread");
    }

    @Test
    void continuationSwitchesToTheExplicitModel() {
        var firstProvider = new RecordingMockLLMProvider();
        firstProvider.addResponse(text("first result"));
        var secondProvider = new RecordingMockLLMProvider();
        secondProvider.addResponse(text("second result"));
        var context = context(firstProvider, new MapPersistence());
        context.setSubagentModelResolver(new FixedModelResolver()
                .put("m1", firstProvider, "p1")
                .put("m2", secondProvider, "p2"));
        var tool = TaskTool.builder().build();

        var first = tool.execute("{\"description\":\"work\",\"prompt\":\"one\",\"subagent_type\":\"explore\",\"task_id\":\"sa-switch\",\"model\":\"m1\"}", context);
        assertTrue(first.isCompleted(), first.getResult());
        assertEquals("m1", firstProvider.requests.getFirst().model);

        var second = tool.execute("{\"description\":\"work\",\"prompt\":\"two\",\"task_id\":\"sa-switch\",\"model\":\"m2\"}", context);

        assertTrue(second.isCompleted(), second.getResult());
        assertEquals(1, secondProvider.requests.size());
        assertEquals("m2", secondProvider.requests.getFirst().model);
        var thread = context.getSubagentThreads().find("sa-switch").orElseThrow();
        assertEquals("m2", thread.model());
        assertEquals("p2", thread.provider());
    }

    @Test
    void rehydrateUsesTheThreadModelInsteadOfTheCurrentConfiguration() {
        var persistence = new MapPersistence();
        var firstProvider = new RecordingMockLLMProvider();
        firstProvider.addResponse(text("first result"));
        var firstContext = context(firstProvider, persistence);
        firstContext.setSubagentModelResolver(new FixedModelResolver().put("m2", firstProvider, "p2"));
        var tool = TaskTool.builder().build();
        var first = tool.execute("{\"description\":\"work\",\"prompt\":\"one\",\"subagent_type\":\"explore\",\"task_id\":\"sa-rehydrate\",\"model\":\"m2\"}", firstContext);
        assertTrue(first.isCompleted(), first.getResult());

        var restoredProvider = new RecordingMockLLMProvider();
        restoredProvider.addResponse(text("restored result"));
        var restoredContext = context(restoredProvider, persistence);
        restoredContext.setSubagentModelResolver(new FixedModelResolver().put("m2", restoredProvider, "p2"));

        var second = tool.execute("{\"description\":\"work\",\"prompt\":\"two\",\"task_id\":\"sa-rehydrate\"}", restoredContext);

        assertTrue(second.isCompleted(), second.getResult());
        assertEquals(1, restoredProvider.requests.size());
        assertEquals("m2", restoredProvider.requests.getFirst().model);
    }

    @Test
    void continuingAThreadKeepsItsModelWhenTheParentModelChanges() {
        var provider = new RecordingMockLLMProvider();
        provider.addResponse(text("first result"));
        provider.addResponse(text("second result"));
        var context = context(provider, new MapPersistence());
        context.setSubagentModelResolver(new FixedModelResolver().put("m1", provider, "p1"));
        var tool = TaskTool.builder().build();
        var first = tool.execute("{\"description\":\"work\",\"prompt\":\"one\",\"subagent_type\":\"explore\",\"task_id\":\"sa-sticky\",\"model\":\"m1\"}", context);
        assertTrue(first.isCompleted(), first.getResult());

        context.setModel("parent-changed");

        var second = tool.execute("{\"description\":\"work\",\"prompt\":\"two\",\"task_id\":\"sa-sticky\"}", context);

        assertTrue(second.isCompleted(), second.getResult());
        assertEquals(2, provider.requests.size());
        assertEquals("m1", provider.requests.get(1).model);
    }


    @Test
    void unknownExplicitModelOnContinuationFailsAsUnknownModel() {
        var provider = new RecordingMockLLMProvider();
        provider.addResponse(text("first result"));
        var context = context(provider, new MapPersistence());
        context.setSubagentModelResolver(new FixedModelResolver().put("m1", provider, "p1"));
        var tool = TaskTool.builder().build();
        var first = tool.execute("{\"description\":\"work\",\"prompt\":\"one\",\"subagent_type\":\"explore\",\"task_id\":\"sa-cont\",\"model\":\"m1\"}", context);
        assertTrue(first.isCompleted(), first.getResult());

        var second = tool.execute("{\"description\":\"work\",\"prompt\":\"two\",\"task_id\":\"sa-cont\",\"model\":\"missing\"}", context);

        assertTrue(second.isFailed());
        assertTrue(second.getResult().contains("[SUBAGENT_MODEL_UNKNOWN]"), second.getResult());
        assertFalse(second.getResult().contains("created with model"), second.getResult());
    }

    @Test
    void continuationFailsWhenTheThreadModelIsNoLongerAvailable() {
        var persistence = new MapPersistence();
        var provider = new RecordingMockLLMProvider();
        provider.addResponse(text("first result"));
        var context = context(provider, persistence);
        context.setSubagentModelResolver(new FixedModelResolver().put("m1", provider, "p1"));
        var tool = TaskTool.builder().build();
        var first = tool.execute("{\"description\":\"work\",\"prompt\":\"one\",\"subagent_type\":\"explore\",\"task_id\":\"sa-lost\",\"model\":\"m1\"}", context);
        assertTrue(first.isCompleted(), first.getResult());

        var restoredContext = context(new RecordingMockLLMProvider(), persistence);
        restoredContext.setSubagentModelResolver(new FixedModelResolver());
        var second = tool.execute("{\"description\":\"work\",\"prompt\":\"two\",\"task_id\":\"sa-lost\"}", restoredContext);

        assertTrue(second.isFailed());
        assertTrue(second.getResult().contains("[SUBAGENT_THREAD_MODEL_UNAVAILABLE]"), second.getResult());
    }

    static class FixedModelResolver implements SubagentModelResolver {
        private final Map<String, ResolvedModel> models = new TreeMap<>();

        FixedModelResolver put(String model, LLMProvider provider, String providerName) {
            models.put(model, new ResolvedModel(model, provider, providerName));
            return this;
        }

        @Override
        public ResolvedModel resolve(String model, String preferredProvider) {
            var resolved = models.get(model);
            if (resolved == null) {
                throw new SubagentModelUnavailableException(SubagentModelUnavailableException.Reason.UNKNOWN_MODEL, model);
            }
            return resolved;
        }

        @Override
        public List<String> availableModels() {
            return List.copyOf(models.keySet());
        }
    }

    static class RecordingMockLLMProvider extends MockLLMProvider {
        final List<CompletionRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            requests.add(request);
            return super.doCompletionStream(request, callback);
        }
    }

    /** Holds the run until the test releases it, so the background thread stays observably busy. */
    static class BlockingMockLLMProvider extends RecordingMockLLMProvider {
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.doCompletionStream(request, callback);
        }
    }

    static class MapPersistence implements PersistenceProvider {
        private final Map<String, String> store = new TreeMap<>();

        @Override
        public void save(String id, String context) {
            store.put(id, context);
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

    private static final class InMemorySink implements SubagentOutputSink {
        @Override
        public void write(String content) {
        }

        @Override
        public String getReference() {
            return "memory://";
        }

        @Override
        public void close() {
        }
    }

    private static final class InMemorySinkFactory implements SubagentOutputSinkFactory {
        @Override
        public SubagentOutputSink create(String taskId) {
            return new InMemorySink();
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
