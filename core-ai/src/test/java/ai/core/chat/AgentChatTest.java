package ai.core.chat;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.tool.function.Functions;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * author: lim chen
 * date: 2025/11/11
 * description:
 */

class AgentChatTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentChatTest.class);
    LLMProvider llmProvider;


    @BeforeEach
    void setUp() {
        llmProvider = Mockito.mock(AzureOpenAIProvider.class);
    }

    @Test
    @Disabled
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

    @Test
    void testChatTool() {
        var wtl = spy(new FakerTools());
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .toolCalls(Functions.from(wtl))
                .maxTurn(1)
                .build();
        CompletionResponse mockResponse = CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.TOOL_CALLS,
                        Message.of(RoleType.ASSISTANT, "", null, null, null,
                                List.of(FunctionCall.of("call_1", "function", "query_person", "{\"name\":\"mock_name\"}")))
                )),
                new Usage(10, 5, 15)
        );

        when(llmProvider.completionStream(any(), any())).thenReturn(mockResponse);
        agent.run("Make scrambled eggs with tomatoes");
        verify(wtl).queryPerson(any());
    }
}

