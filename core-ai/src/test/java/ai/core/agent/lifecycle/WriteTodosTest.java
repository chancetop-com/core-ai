package ai.core.agent.lifecycle;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * author: lim chen
 * date: 2025/11/10
 * description:
 */
class WriteTodosTest {
    LLMProvider llmProvider;


    @BeforeEach
    void setUp() {
        llmProvider = Mockito.mock(AzureOpenAIProvider.class);
    }

    @Test
    void testAgentAdd() {
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .agentLifecycle(List.of(new WriteTodosLifecycle()))
                .build();

        assertFalse(agent.getToolCalls().isEmpty());
        assertTrue(agent.getSystemPrompt().contains("write_todos"));
    }

    @Test
    void testTodoFunctionCall() {
        var wtl = spy(new WriteTodosLifecycle());
        var agent = Agent.builder()
                .llmProvider(llmProvider)
                .agentLifecycle(List.of(wtl))
                .maxToolCallCount(1)
                .build();
        String cc = """
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"我来帮您制作西红柿炒鸡蛋，并使用待办事项来管理这个烹饪任务。","name":"assistant","tool_call_id":null,"function_call":null,"tool_calls":[{"id":"call_00_05JqTTzdrNShvtI2twP3uuBa","type":"function","function":{"name":"write_todos","arguments":"{\\"todos\\": [{\\"content\\": \\"准备食材：西红柿2个、鸡蛋3个、葱姜适量、盐、糖、食用油\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"清洗西红柿并切成小块\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"打散鸡蛋，加入少许盐调味\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"切葱花和姜末备用\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"热锅倒油，炒熟鸡蛋后盛出\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"重新热锅，爆香葱姜\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"加入西红柿翻炒至软烂出汁\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"加入炒好的鸡蛋，加盐和糖调味\\", \\"status\\": \\"PENDING\\"}, {\\"content\\": \\"翻炒均匀后出锅装盘\\", \\"status\\": \\"PENDING\\"}]}"},"index":null}]},"delta":null,"index":null}],"usage":{"prompt_tokens":652,"completion_tokens":223,"total_tokens":-1}}
                """;
        var crs = JsonUtil.fromJson(CompletionResponse.class, cc);

        when(llmProvider.completion(any())).thenReturn(crs);
        agent.run("做一个西红柿炒鸡蛋", new HashMap<>());
        verify(wtl).writeTodos(anyList());

    }
}
