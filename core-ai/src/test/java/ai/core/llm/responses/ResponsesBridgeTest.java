package ai.core.llm.responses;

import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.CaptionImageResponse;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.RerankingRequest;
import ai.core.llm.domain.RerankingResponse;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesBridgeTest {
    @Test
    void streamsThroughProviderAndReturnsCompletedResponse() {
        var provider = new ScriptedProvider();
        var bridge = new ResponsesBridge(provider);
        var request = JsonUtil.fromJson(ResponsesRequest.class, """
                {"model":"m","stream":true,"input":"hello","max_output_tokens":10}
                """);
        var events = new ArrayList<Event>();

        var response = bridge.stream(request, (type, data) -> events.add(new Event(type, data)));

        assertEquals("completed", response.status);
        assertEquals(10, provider.lastRequest.maxCompletionTokens);
        assertEquals(Boolean.TRUE, provider.lastRequest.stream);
        assertNotNull(provider.lastRequest.streamOptions);
        assertTrue(events.stream().map(Event::type).toList().contains("response.output_text.delta"));
        assertEquals("response.completed", events.getLast().type);

        var completed = JsonUtil.toMap(events.getLast().data);
        var completedResponse = (Map<?, ?>) completed.get("response");
        assertEquals("completed", completedResponse.get("status"));
    }

    private static class ScriptedProvider extends LLMProvider {
        CompletionRequest lastRequest;

        ScriptedProvider() {
            super(new LLMProviderConfig("default-model", 0.0, "embedding-model"));
        }

        @Override
        protected CompletionResponse doCompletion(CompletionRequest request) {
            return finalResponse();
        }

        @Override
        protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
            lastRequest = request;
            callback.onRawData("""
                    {"choices":[{"index":0,"delta":{"role":"assistant","content":"pong"},"finish_reason":"stop"}]}
                    """);
            callback.onComplete();
            return finalResponse();
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
            return "scripted";
        }

        private CompletionResponse finalResponse() {
            var message = new AssistantMessage();
            message.role = RoleType.ASSISTANT;
            message.content = "pong";
            var choice = new Choice();
            choice.message = message;
            choice.finishReason = FinishReason.STOP;
            return CompletionResponse.of(List.of(choice), new Usage(1, 1, 2));
        }
    }

    private record Event(String type, String data) {
    }
}
