package ai.core.agent.lifecycle;

import ai.core.agent.Agent;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * author: lim chen
 * date: 2025/11/10
 * description: Unit tests for AbstractLifecycle and its implementations
 */
class AgentLifecycleTest {
    LLMProvider llmProvider;


    @BeforeEach
    void setUp() {
        llmProvider = Mockito.mock(AzureOpenAIProvider.class);
    }


    @Test
    void testWrapModel() {
        String cc = """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"你好呀！😊 很高兴见到你！✨","name":"assistant","tool_call_id":null,"function_call":null,"tool_calls":null},"delta":null,"index":null}],"usage":{"prompt_tokens":6,"completion_tokens":80,"total_tokens":-1}}
                """;
        var flc = spy(new FakerLifecycle());
        var crs = JsonUtil.fromJson(CompletionResponse.class, cc);
        when(llmProvider.completion(any())).thenReturn(crs);
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .agentLifecycle(List.of(flc))
                .build();
        agent.run("你好", new HashMap<>());
        verify(flc).beforeModel(argThat(req -> Objects.equals(req.model, "test_model")));
        verify(flc).afterModel(argThat(res -> res.usage.getTotalTokens() == 1));
    }

    static class FakerLifecycle extends AbstractLifecycle implements StreamingCallback {
        private StreamingCallback cb;

        @Override
        public void beforeModel(CompletionRequest completionRequest) {
            completionRequest.model = "test_model";

        }

        @Override
        public void afterModel(CompletionResponse completionResponse) {
            completionResponse.usage = new Usage(1, 1, 1);
        }

        @Override
        public StreamingCallback afterModelStream(StreamingCallback callback) {
            this.cb = callback;
            return this;
        }

        @Override
        public void onChunk(String chunk) {
            var tChunk = chunk;
            tChunk = tChunk + "\n";
            cb.onChunk(tChunk);
        }

        @Override
        public void onComplete() {
            cb.onComplete();

        }

        @Override
        public void onError(Throwable error) {
            cb.onError(error);

        }
    }
}
