package ai.core.chat;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * author: lim chen
 * date: 2025/11/11
 * description:
 */

@Disabled
class AgentChatTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentChatTest.class);
    LLMProvider llmProvider;


    @BeforeEach
    void setUp() {
        llmProvider = Mockito.mock(AzureOpenAIProvider.class);
    }

    @Test
    void testStreamChat() {
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .streamingCallback(new StreamingCallback() {
                    @Override
                    public void onChunk(String chunk) {
                        LOGGER.info("{}", chunk);
                    }

                    @Override
                    public void onComplete() {
                    }

                    @Override
                    public void onError(Throwable error) {
                    }
                })
                .build();
        agent.run("Hello", ExecutionContext.builder().build());


    }

    @Test
    void testChat() {
        String cc = """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"hello, i am deepseek","name":"assistant","tool_call_id":null,"function_call":null,"tool_calls":null},"delta":null,"index":null}],"usage":{"prompt_tokens":6,"completion_tokens":80,"total_tokens":-1}}
                """;
        var crs = JsonUtil.fromJson(CompletionResponse.class, cc);
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .build();
        when(llmProvider.completionStream(any(CompletionRequest.class), any(StreamingCallback.class))).thenReturn(crs);
        String out = agent.run("Hello", ExecutionContext.builder().build());
        assert out != null;

    }
}
