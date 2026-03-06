package ai.core.agent;

import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoomLoopIntegrationTest {

    private static CompletionResponse toolCallResponse(String toolName, String args) {
        return CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.TOOL_CALLS,
                        Message.of(RoleType.ASSISTANT, "", null, null,
                                List.of(FunctionCall.of("call_1", "function", toolName, args)))
                )),
                new Usage(10, 20, 30)
        );
    }

    private static CompletionResponse simpleResponse(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30)
        );
    }

    private static ToolCall createSimpleTool(String toolName, ToolCallResult result) {
        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return result;
            }
        };
        tool.setName(toolName);
        tool.setDescription("test tool: " + toolName);
        tool.setParameters(List.of());
        return tool;
    }

    @Test
    void doomLoopStopsRepeatedToolCalls() {
        var provider = new MockLLMProvider();
        provider.addResponse(toolCallResponse("failing_tool", "{\"path\": \"/missing\"}"));
        provider.addResponse(toolCallResponse("failing_tool", "{\"path\": \"/missing\"}"));
        provider.addResponse(toolCallResponse("failing_tool", "{\"path\": \"/missing\"}"));
        provider.addResponse(simpleResponse("I'll try a different approach."));

        var failingTool = createSimpleTool("failing_tool", ToolCallResult.failed("File not found"));

        var agent = Agent.builder()
                .llmProvider(provider)
                .toolCalls(List.of(failingTool))
                .maxTurn(10)
                .doomLoopWindowSize(3)
                .build();

        var result = agent.run("read the file");
        assertNotNull(result);

        var messages = agent.getMessages();
        var hasDoomMessage = messages.stream()
                .filter(m -> m.role == RoleType.TOOL)
                .anyMatch(m -> m.getTextContent().contains("doom loop"));
        assertTrue(hasDoomMessage, "doom loop error message should appear in tool results");
    }

    @Test
    void doomLoopDisabledAllowsRepeatedCalls() {
        var provider = new MockLLMProvider();
        provider.addResponse(toolCallResponse("test_tool", "{}"));
        provider.addResponse(toolCallResponse("test_tool", "{}"));
        provider.addResponse(simpleResponse("done"));

        var testTool = createSimpleTool("test_tool", ToolCallResult.completed("ok"));

        var agent = Agent.builder()
                .llmProvider(provider)
                .toolCalls(List.of(testTool))
                .maxTurn(3)
                .doomLoopDetection(false)
                .build();

        var result = agent.run("test");
        assertNotNull(result);

        var messages = agent.getMessages();
        var hasDoomMessage = messages.stream()
                .filter(m -> m.role == RoleType.TOOL)
                .anyMatch(m -> m.getTextContent().contains("doom loop"));
        assertFalse(hasDoomMessage, "no doom loop message when detection is disabled");
    }
}
