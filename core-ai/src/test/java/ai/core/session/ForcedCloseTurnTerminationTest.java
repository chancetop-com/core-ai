package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.streaming.StreamingCallback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard: closing a session while a turn is in flight (idle cleanup, pod shutdown) must still end the
 * turn. A turn left without a terminal event leaves the cross-pod turn state marked running forever,
 * which pins the chat UI to a spinner it can never recover from.
 *
 * @author stephen
 */
class ForcedCloseTurnTerminationTest {

    private static void awaitTurnFinished(InProcessAgentSession session) throws InterruptedException {
        var deadline = System.currentTimeMillis() + 10_000;
        while (session.isTurnRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void closeWhileTurnRunningTerminatesTheTurnExactlyOnce() throws InterruptedException {
        var turnEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var agent = Agent.builder().llmProvider(new BlockingLLMProvider(turnEntered, release)).build();
        var session = new InProcessAgentSession("s-forced-close", agent, true, new InMemoryToolPermissionStore());

        var recorder = new TerminalEventRecorder();
        session.onEvent(recorder);

        session.sendMessage("hello");
        assertTrue(turnEntered.await(10, TimeUnit.SECONDS), "turn should have started");
        assertTrue(session.isTurnRunning(), "turn should report as running while the LLM call is in flight");

        session.close();

        assertTrue(recorder.terminated.await(10, TimeUnit.SECONDS), "closing must end the in-flight turn");
        assertEquals(List.of("complete:cancelled=true"), recorder.turnEndings, "forced close ends the turn as cancelled");

        // Let the stuck turn thread unwind: its own terminal dispatch must lose the race and stay silent.
        release.countDown();
        awaitTurnFinished(session);
        assertEquals(1, recorder.turnEndings.size(), "the turn must be terminated exactly once");
        assertEquals(1, recorder.terminalStatuses.size(), "exactly one terminal status change");
        assertEquals(SessionStatus.IDLE, recorder.terminalStatuses.getFirst());
        assertFalse(session.isTurnRunning());
    }

    @Test
    void closingAnIdleSessionEmitsNoTurnEvents() throws InterruptedException {
        var agent = Agent.builder().llmProvider(new BlockingLLMProvider(new CountDownLatch(1), new CountDownLatch(0))).build();
        var session = new InProcessAgentSession("s-idle-close", agent, true, new InMemoryToolPermissionStore());
        var recorder = new TerminalEventRecorder();
        session.onEvent(recorder);

        session.close();

        assertTrue(recorder.turnEndings.isEmpty(), "a session that never ran a turn must not fabricate one on close");
        assertTrue(recorder.terminalStatuses.isEmpty(), "and must not emit a terminal status either");
    }

    private static final class TerminalEventRecorder implements AgentEventListener {
        private final List<String> turnEndings = new CopyOnWriteArrayList<>();
        private final List<SessionStatus> terminalStatuses = new CopyOnWriteArrayList<>();
        private final CountDownLatch terminated = new CountDownLatch(1);

        @Override
        public void onTurnComplete(TurnCompleteEvent event) {
            turnEndings.add("complete:cancelled=" + event.cancelled);
            terminated.countDown();
        }

        @Override
        public void onError(ErrorEvent event) {
            turnEndings.add("error");
            terminated.countDown();
        }

        @Override
        public void onStatusChange(StatusChangeEvent event) {
            if (event.status != SessionStatus.RUNNING) terminalStatuses.add(event.status);
        }
    }

    /** Blocks inside the LLM call and stays blocked through an interrupt, standing in for a turn stuck on IO. */
    private static final class BlockingLLMProvider extends LLMProvider {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingLLMProvider(CountDownLatch entered, CountDownLatch release) {
            super(new LLMProviderConfig("mock-model", 0.7, "mock-embedding-model"));
            this.entered = entered;
            this.release = release;
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            entered.countDown();
            var interrupted = false;
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            throw new IllegalStateException("released after forced close");
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
}
