package ai.core.llm;

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
import ai.core.llm.streaming.FirstTokenListener;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.telemetry.LLMTracer;
import ai.core.telemetry.RecordingSpanProcessor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The first-token measurement must reach the span before the call returns and reach the host's
 * first-token sink with the same value; both feed the live view and the latency dashboard.
 *
 * @author stephen
 */
class LLMProviderTtftTest {
    @Test
    void recordsTtftOnTheSpanAndNotifiesTheFirstTokenSink() {
        var processor = new RecordingSpanProcessor();
        var tracerProvider = SdkTracerProvider.builder().addSpanProcessor(processor).build();
        var tracer = new LLMTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build(), true);
        var provider = new StreamingProvider();
        provider.setTracer(tracer);
        var callback = new ObservingCallback();
        var request = CompletionRequest.of(List.of(Message.of(RoleType.USER, "hi")), List.of(), 0.0, "test-model", "ttft-test");

        var response = provider.completionStream(request, callback, null, true);

        assertNotNull(response.ttftMs);
        assertNotNull(response.firstContentMs);
        assertEquals(response.ttftMs, callback.firstTokens.getFirst());
        var span = processor.find("ttft-test").orElseThrow();
        assertEquals(response.ttftMs, span.getAttributes().get(AttributeKey.longKey("gen_ai.response.time_to_first_token_ms")));
        assertEquals(response.firstContentMs, span.getAttributes().get(AttributeKey.longKey("gen_ai.response.first_content_ms")));
        tracerProvider.close();
    }

    private static final class ObservingCallback implements StreamingCallback, FirstTokenListener {
        private final List<Long> firstTokens = new ArrayList<>();

        @Override
        public void onChunk(String chunk) {
        }

        @Override
        public void onFirstToken(long ttftMs) {
            firstTokens.add(ttftMs);
        }
    }

    private static final class StreamingProvider extends LLMProvider {
        StreamingProvider() {
            super(new LLMProviderConfig("test-model", 0.0, null));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            throw new UnsupportedOperationException("not used in tests");
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            callback.onChunk("hello");
            return CompletionResponse.of(List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, "hello"))), null);
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
            return "streaming-llm";
        }
    }
}
