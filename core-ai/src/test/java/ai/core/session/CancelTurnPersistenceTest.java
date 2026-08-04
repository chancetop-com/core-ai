package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.CancelReason;
import ai.core.agent.CancellationException;
import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
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
import ai.core.llm.streaming.StreamingCallback;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that cancelling an in-flight turn (CLI ESC) persists the interruption
 * marker and the turn's user message immediately, so the turn survives process death.
 *
 * @author stephen
 */
class CancelTurnPersistenceTest {

    private static AgentEventListener cancelledTurnListener(CountDownLatch runningLatch, CountDownLatch cancelledLatch,
                                                            AtomicReference<TurnCompleteEvent> cancelledEvent) {
        return new AgentEventListener() {
            @Override
            public void onStatusChange(StatusChangeEvent event) {
                if (event.status == SessionStatus.RUNNING) runningLatch.countDown();
            }

            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                if (!Boolean.TRUE.equals(event.cancelled)) return;
                if (cancelledEvent != null) cancelledEvent.set(event);
                cancelledLatch.countDown();
            }
        };
    }

    @Test
    void cancelTurnPersistsUserMessageAndInterruptionMarker() throws InterruptedException {
        var store = new RunFailurePersistenceTest.InMemoryPersistenceProvider();
        var provider = new BlockingLLMProvider();
        var agent = Agent.builder()
                .llmProvider(provider)
                .persistenceProvider(store)
                .build();
        var context = ExecutionContext.builder()
                .sessionId("test-cancel")
                .persistenceProvider(store)
                .build();
        agent.setExecutionContext(context);

        var session = new InProcessAgentSession("test-cancel", agent, true, new InMemoryToolPermissionStore());
        var runningLatch = new CountDownLatch(1);
        var cancelledLatch = new CountDownLatch(1);
        session.onEvent(cancelledTurnListener(runningLatch, cancelledLatch, null));

        session.sendMessage("hello");
        assertTrue(runningLatch.await(10, TimeUnit.SECONDS), "turn should start");
        // simulate ESC: cancel the in-flight turn
        session.cancelTurn();
        assertTrue(cancelledLatch.await(10, TimeUnit.SECONDS), "turn should be cancelled");

        var saved = store.load("test-cancel");
        assertTrue(saved.isPresent(), "session should be persisted right after cancelTurn");
        assertTrue(saved.get().contains("hello"), "persisted session should contain the cancelled turn user message");
        assertTrue(saved.get().contains("system-reminder"), "persisted session should contain the interruption marker");

        session.close();
    }

    @Test
    void cancelledTurnCarriesPartialOutput() throws InterruptedException {
        var store = new RunFailurePersistenceTest.InMemoryPersistenceProvider();
        var provider = new PartialThenBlockLLMProvider();
        var agent = Agent.builder()
                .llmProvider(provider)
                .persistenceProvider(store)
                .build();
        var context = ExecutionContext.builder()
                .sessionId("test-partial")
                .persistenceProvider(store)
                .build();
        agent.setExecutionContext(context);

        var session = new InProcessAgentSession("test-partial", agent, true, new InMemoryToolPermissionStore());
        var runningLatch = new CountDownLatch(1);
        var cancelledEvent = new AtomicReference<TurnCompleteEvent>();
        var cancelledLatch = new CountDownLatch(1);
        session.onEvent(cancelledTurnListener(runningLatch, cancelledLatch, cancelledEvent));

        session.sendMessage("hello");
        assertTrue(runningLatch.await(10, TimeUnit.SECONDS), "turn should start");
        // wait until the partial chunk was actually produced, then simulate ESC
        assertTrue(provider.partialProduced.await(10, TimeUnit.SECONDS), "partial chunk should be produced");
        session.cancelTurn();
        assertTrue(cancelledLatch.await(10, TimeUnit.SECONDS), "turn should be cancelled");

        var event = cancelledEvent.get();
        assertTrue(event.output.contains("partial answer"),
                "cancelled event should carry partial output, got: " + event.output);

        var saved = store.load("test-partial");
        assertTrue(saved.isPresent(), "session should be persisted after cancelled turn");
        assertTrue(saved.get().contains("partial answer"),
                "persisted session should contain partial assistant output of the cancelled turn");

        session.close();
    }

    static class BlockingLLMProvider extends LLMProvider {
        private final AtomicBoolean started = new AtomicBoolean();

        BlockingLLMProvider() {
            super(new LLMProviderConfig("block-model", 0.7, "block-embedding-model"));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            started.set(true);
            while (!callback.isCancelled()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // like a provider whose SSE stream is interrupted mid-turn with no partial content
            throw new CancellationException(CancelReason.USER_CANCELLED);
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public int maxTokens() {
            return 4096;
        }

        @Override
        public String name() {
            return "blocking-llm";
        }
    }

    static class PartialThenBlockLLMProvider extends LLMProvider {
        final CountDownLatch partialProduced = new CountDownLatch(1);

        PartialThenBlockLLMProvider() {
            super(new LLMProviderConfig("block-model", 0.7, "block-embedding-model"));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            callback.onChunk("partial answer");
            partialProduced.countDown();
            while (!callback.isCancelled()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // mimic the provider cancel path: partial content + [interrupted] suffix
            return CompletionResponse.of(
                    List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "partial answer\n\n[interrupted]"))),
                    new Usage(10, 20, 30));
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public int maxTokens() {
            return 4096;
        }

        @Override
        public String name() {
            return "partial-blocking-llm";
        }
    }
}
