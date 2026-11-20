package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.session.lifecycle.TaskRunCoordinator;
import ai.core.session.lifecycle.TaskRunEnded;
import ai.core.session.lifecycle.TaskRunObserver;
import ai.core.session.lifecycle.TaskRunOutcome;
import ai.core.session.lifecycle.TaskRunSnapshot;
import ai.core.session.lifecycle.TaskRunState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class TaskRunLifecycleIntegrationTest {
    private static CompletionResponse response(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30));
    }

    @Test
    void aNormalTurnReachesReturnedOnlyAfterTheDriverHasReleasedIt() throws InterruptedException {
        var provider = new MockLLMProvider();
        provider.addResponse(response("done"));
        var agent = Agent.builder().llmProvider(provider).build();
        var session = new InProcessAgentSession("s-task-run", agent, true, new InMemoryToolPermissionStore());
        var recorder = new Recorder();
        session.taskRuns().setObserver(recorder);
        try {
            var opened = session.taskRuns().openRoot("request-1");
            session.sendMessage("hello");

            assertTrue(recorder.done.await(10, TimeUnit.SECONDS), "the run must end after the turn");
            assertEquals(TaskRunOutcome.RETURNED, recorder.ended.get().outcome());
            assertEquals(TaskRunState.TERMINAL, session.taskRuns().lastEndedSnapshot().state());
            assertEquals(opened.taskRunId(), session.taskRuns().lastEndedSnapshot().taskRunId());
        } finally {
            session.close();
        }
    }

    @Test
    void anUntrackedRemoteMediaLaunchCannotClaimReturned() throws InterruptedException {
        var provider = new MockLLMProvider();
        provider.addResponse(response("submitted"));
        var agent = Agent.builder().llmProvider(provider).build();
        var session = new InProcessAgentSession("s-task-run-media", agent, true, new InMemoryToolPermissionStore());
        var turnDone = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                turnDone.countDown();
            }
        });
        try {
            session.taskRuns().openRoot("request-1");
            session.dispatchEvent(ToolResultEvent.of(session.id(), "call-1", "generate_video", "pending", "video-1"));
            session.sendMessage("hello");
            assertTrue(turnDone.await(10, TimeUnit.SECONDS));

            var snapshot = session.taskRuns().activeSnapshot();
            assertTrue(snapshot != null, "the run must stay open for the untracked remote work");
            assertEquals("untrackable", snapshot.coverage());
            assertEquals(TaskRunState.NEEDS_ATTENTION, snapshot.state());
            assertTrue(session.taskRuns().lastEndedSnapshot() == null);
        } finally {
            session.close();
        }
    }

    @Test
    void aQueuedRootDoesNotStartUntilTheActiveRunSettles() throws InterruptedException {
        var provider = new MockLLMProvider();
        provider.addResponse(response("queued task ran"));
        var agent = Agent.builder().llmProvider(provider).build();
        var session = new InProcessAgentSession("s-queued-run", agent, true, new InMemoryToolPermissionStore());
        var turnDone = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                turnDone.countDown();
            }
        });
        try {
            var first = session.taskRuns().openRoot("request-1", "turn-1");
            session.taskRuns().executionStarted("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, 1);
            session.taskRuns().taskLaunched("job-1", 1L);
            session.taskRuns().executionEnded("turn-1", TaskRunCoordinator.ExecutionKind.ROOT, TaskRunOutcome.RETURNED, null);
            var queued = session.taskRuns().openRoot("request-2", "turn-2");
            assertEquals(TaskRunState.QUEUED, queued.state());

            session.sendMessage("second task", null, null, "turn-2");
            Thread.sleep(300);
            assertEquals(first.taskRunId(), session.taskRuns().activeSnapshot().taskRunId());
            assertTrue(!turnDone.await(200, TimeUnit.MILLISECONDS), "the queued task must not start while required work is open");

            session.taskRuns().taskSettled("job-1", 1L, TaskRunOutcome.RETURNED, false, "completed");
            assertTrue(turnDone.await(10, TimeUnit.SECONDS), "the queued task starts after the active run ends");
            assertEquals(queued.taskRunId(), session.taskRuns().lastEndedSnapshot().taskRunId());
            assertEquals(TaskRunOutcome.RETURNED, session.taskRuns().lastEndedSnapshot().outcome());
        } finally {
            session.close();
        }
    }

    @Test
    void closingTheSessionInterruptsTheRunWithoutFabricatingSuccess() throws InterruptedException {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var agent = Agent.builder().llmProvider(new BlockingProvider(entered, release)).build();
        var session = new InProcessAgentSession("s-task-run-close", agent, true, new InMemoryToolPermissionStore());
        var recorder = new Recorder();
        session.taskRuns().setObserver(recorder);
        try {
            session.taskRuns().openRoot("request-1");
            session.sendMessage("hello");
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the turn should be in flight");

            session.close();

            assertTrue(recorder.done.await(10, TimeUnit.SECONDS));
            assertEquals(TaskRunOutcome.INTERRUPTED, recorder.ended.get().outcome());
        } finally {
            release.countDown();
        }
    }

    private static final class BlockingProvider extends LLMProvider {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingProvider(CountDownLatch entered, CountDownLatch release) {
            super(new LLMProviderConfig("mock-model", 0.7, "mock-embedding-model"));
            this.entered = entered;
            this.release = release;
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("released");
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            return doCompletion(request);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String name() {
            return "blocking-mock";
        }
    }

    private static final class Recorder implements TaskRunObserver {
        private final AtomicReference<TaskRunEnded> ended = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);

        @Override
        public void onTaskRunEnded(TaskRunSnapshot snapshot, TaskRunEnded event) {
            ended.set(event);
            done.countDown();
        }
    }
}
