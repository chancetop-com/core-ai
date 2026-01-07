package ai.core.agent;

import ai.core.agent.lifecycle.FakerLifecycle;
import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.tool.function.Functions;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * author: lim chen
 * date: 2025/11/10
 * description: Unit tests for AbstractLifecycle and its implementations
 */
class AgentLifecycleTest {
    AzureOpenAIProvider llmProvider;


    @BeforeEach
    void setUp() {
        llmProvider = mock(AzureOpenAIProvider.class);
    }


    @Test
    void testSimpleChat() {
        String cc = """
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"hello, i am deepseek","name":"assistant","tool_call_id":null,"function_call":null,"tool_calls":null},"delta":null,"index":null}],"usage":{"prompt_tokens":6,"completion_tokens":80,"total_tokens":-1}}
                """;
        var flc = new FakerLifecycle.FakerLifecycleInner();
        var crs = JsonUtil.fromJson(CompletionResponse.class, cc);
        var requestCaptor = ArgumentCaptor.forClass(CompletionRequest.class);
        var queryCaptor = ArgumentCaptor.forClass(String.class);
        when(llmProvider.completionStream(any(CompletionRequest.class), any(StreamingCallback.class))).thenReturn(crs);
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .agentLifecycle(List.of(flc))
                .build();
        var agentSpy = spy(agent);
        var agentOut = agentSpy.run("hello");
        verify(llmProvider).completionStream(requestCaptor.capture(), any(StreamingCallback.class));
        verify(agentSpy).chatTurns(queryCaptor.capture(), any(), any());
        // check before build
        assertEquals(1000, agentSpy.getMaxRound());
        // check after build
        assertEquals("mock_system_prompt", agentSpy.getSystemPrompt());
        //check before agent
        assertTrue(queryCaptor.getValue().contains("mock_query"));
        //check after agent
        assertTrue(agentOut.contains("mock_test_result"));
        // check before model
        assertEquals("mock_model", requestCaptor.getValue().model);
        // check after model
        assertEquals(1, crs.usage.getCompletionTokens());
    }


    @Test
    void testToolCall() {
        String cc = """
                {
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "role": "assistant",
                        "content": "",
                        "name": "assistant",
                        "tool_call_id": null,
                        "function_call": null,
                        "tool_calls": [
                          {
                            "id": "call_00_05JqTTzdrNShvtI2twP3uuBa",
                            "type": "function",
                            "function": {
                              "name": "query_person",
                              "arguments": "{\\"name\\":\\"ccct\\"}"
                            },
                            "index": null
                          }
                        ]
                      },
                      "delta": null,
                      "index": null
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 652,
                    "completion_tokens": 223,
                    "total_tokens": -1
                  }
                }
                """;
        var flcInner = spy(new FakerLifecycle.FakerLifecycleInner());
        var flc = spy(new FakerLifecycle());
        var crs = JsonUtil.fromJson(CompletionResponse.class, cc);
        var nameCaptor = ArgumentCaptor.forClass(String.class);
        when(llmProvider.completionStream(any(CompletionRequest.class), any(StreamingCallback.class))).thenReturn(crs);
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .maxTurn(1)
                .toolCalls(Functions.from(flc, "queryPerson"))
                .agentLifecycle(List.of(flcInner))
                .build();
        var agentSpy = spy(agent);
        agentSpy.run("hello");
        verify(flc).queryPerson(nameCaptor.capture());
        // before tool
        assertTrue(nameCaptor.getValue().contains("mock_name"));
        // after tool
        assertEquals("mock_name_mock_tool_result", agentSpy.getMessages().getLast().content);
    }

    @Test
    void testToolCallWithException() {
        String cc = """
                {
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "role": "assistant",
                        "content": "",
                        "name": "assistant",
                        "tool_call_id": null,
                        "function_call": null,
                        "tool_calls": [
                          {
                            "id": "call_00_05JqTTzdrNShvtI2twP3uuBa",
                            "type": "function",
                            "function": {
                              "name": "query_person",
                              "arguments": "{\\"name\\":\\"ttt\\"}"
                            },
                            "index": null
                          }
                        ]
                      },
                      "delta": null,
                      "index": null
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 652,
                    "completion_tokens": 223,
                    "total_tokens": -1
                  }
                }
                """;
        var flcInner = spy(new FakerLifecycle.FakerLifecycleInner2());
        var flc = spy(new FakerLifecycle());
        var crs = JsonUtil.fromJson(CompletionResponse.class, cc);
        when(llmProvider.completionStream(any(CompletionRequest.class), any(StreamingCallback.class))).thenReturn(crs);
        when(flc.queryPerson(any())).thenThrow(new RuntimeException("mock_tool_call_exception"));
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .maxTurn(1)
                .toolCalls(Functions.from(flc, "queryPerson"))
                .agentLifecycle(List.of(flcInner))
                .build();
        var agentSpy = spy(agent);
        assertThrows(RuntimeException.class, () -> agentSpy.run("hello"));
        verify(flcInner).afterTool(any(), any(), any());

    }


}
