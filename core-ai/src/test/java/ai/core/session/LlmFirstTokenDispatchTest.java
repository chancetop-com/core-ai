package ai.core.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.LlmFirstTokenEvent;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A model call's first-token latency must reach the session listeners at the first token and land
 * on the turn summary, so the live view and the history report the same wait.
 *
 * @author stephen
 */
class LlmFirstTokenDispatchTest {
    @Test
    void aModelCallReportsItsTtftAndTheTurnCarriesTheTotal() throws InterruptedException {
        var agent = Agent.builder().llmProvider(new SlowFirstTokenProvider()).maxTurn(2).build();
        var session = new InProcessAgentSession("ttft-session", agent, true, new InMemoryToolPermissionStore());
        var ttftEvents = new CopyOnWriteArrayList<LlmFirstTokenEvent>();
        var turnComplete = new AtomicReference<TurnCompleteEvent>();
        var latch = new CountDownLatch(1);
        session.onEvent(new AgentEventListener() {
            @Override
            public void onLlmFirstToken(LlmFirstTokenEvent event) {
                ttftEvents.add(event);
            }

            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                turnComplete.set(event);
                latch.countDown();
            }
        });

        session.sendMessage("hello");

        assertTrue(latch.await(10, TimeUnit.SECONDS), "turn should complete");
        assertEquals(1, ttftEvents.size());
        assertTrue(ttftEvents.getFirst().ttftMs >= 20L, "unexpected ttft: " + ttftEvents.getFirst().ttftMs);
        assertNotNull(turnComplete.get().waitingMs);
        assertEquals(ttftEvents.getFirst().ttftMs, turnComplete.get().waitingMs);
        session.close();
    }

    private static final class SlowFirstTokenProvider extends LLMProvider {
        SlowFirstTokenProvider() {
            super(new LLMProviderConfig("test-model", 0.0, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            callback.onChunk("hello");
            return CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "hello"))),
                    new Usage(1, 1, 2));
        }

        @Override
        public EmbeddingResponse embeddings(EmbeddingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public RerankingResponse rerankings(RerankingRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public CaptionImageResponse captionImage(CaptionImageRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        public String name() {
            return "slow-first-token-llm";
        }
    }
}
