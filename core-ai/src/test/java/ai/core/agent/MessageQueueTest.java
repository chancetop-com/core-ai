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

class MessageQueueTest {

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

    @Test
    void queueMessageAndHasPendingMessages() {
        var provider = new MockLLMProvider();
        provider.addResponse(simpleResponse("ok"));

        var agent = Agent.builder().llmProvider(provider).build();
        assertFalse(agent.hasPendingMessages());

        agent.queueMessage("new instruction");
        assertTrue(agent.hasPendingMessages());
    }

    @Test
    void pendingMessagesInjectedBetweenTurns() {
        var provider = new MockLLMProvider();
        var callCount = new int[]{0};

        var slowTool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("tool result " + (++callCount[0]));
            }
        };
        slowTool.setName("test_tool");
        slowTool.setDescription("test tool");
        slowTool.setParameters(List.of());

        // Turn 1: tool call
        provider.addResponse(toolCallResponse("test_tool", "{}"));
        // Turn 2: after pending message is injected, LLM responds to both
        provider.addResponse(simpleResponse("Acknowledged your new message. Here's the result."));

        var agent = Agent.builder()
                .llmProvider(provider)
                .toolCalls(List.of(slowTool))
                .maxTurn(3)
                .build();

        // Queue a message before running (simulating user typing during execution)
        agent.queueMessage("please also check the tests");

        var result = agent.run("run the tool");
        assertNotNull(result);

        // Verify the queued message was injected as a USER message
        var messages = agent.getMessages();
        var hasQueuedMessage = messages.stream()
                .filter(m -> m.role == RoleType.USER)
                .anyMatch(m -> m.getTextContent() != null && m.getTextContent().contains("please also check the tests"));
        assertTrue(hasQueuedMessage, "queued message should be injected into conversation");
    }

    @Test
    void multipleQueuedMessagesAreDrained() {
        var provider = new MockLLMProvider();
        provider.addResponse(toolCallResponse("test_tool", "{}"));
        provider.addResponse(simpleResponse("done"));

        var tool = new ToolCall() {
            @Override
            public ToolCallResult execute(String arguments) {
                return ToolCallResult.completed("ok");
            }
        };
        tool.setName("test_tool");
        tool.setDescription("test");
        tool.setParameters(List.of());

        var agent = Agent.builder()
                .llmProvider(provider)
                .toolCalls(List.of(tool))
                .maxTurn(3)
                .build();

        agent.queueMessage("message one");
        agent.queueMessage("message two");

        agent.run("start");

        // After drain, no more pending messages
        assertFalse(agent.hasPendingMessages());

        // Both messages should appear in a single USER message
        var messages = agent.getMessages();
        var injectedMessages = messages.stream()
                .filter(m -> m.role == RoleType.USER)
                .filter(m -> m.getTextContent() != null && m.getTextContent().contains("system-reminder"))
                .toList();
        assertTrue(!injectedMessages.isEmpty(), "queued messages should be injected");
    }

    @Test
    void noInjectionWhenQueueEmpty() {
        var provider = new MockLLMProvider();
        provider.addResponse(simpleResponse("hello"));

        var agent = Agent.builder()
                .llmProvider(provider)
                .maxTurn(1)
                .build();

        agent.run("hi");

        // Only SYSTEM + USER + ASSISTANT messages (no extra USER from queue)
        var userMessages = agent.getMessages().stream()
                .filter(m -> m.role == RoleType.USER)
                .count();
        assertTrue(userMessages == 1, "should have exactly 1 user message when queue is empty");
    }
}
