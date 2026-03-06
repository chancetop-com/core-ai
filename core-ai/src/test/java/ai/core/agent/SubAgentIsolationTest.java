package ai.core.agent;

import ai.core.agent.streaming.StreamingCallback;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.tool.tools.SubAgentToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentIsolationTest {

    private static CompletionResponse simpleResponse(String content) {
        return CompletionResponse.of(
                List.of(Choice.of(FinishReason.STOP, Message.of(RoleType.ASSISTANT, content))),
                new Usage(10, 20, 30)
        );
    }

    @Test
    void subAgentMessagesDoNotBubbleToParent() {
        var subAgentProvider = new MockLLMProvider();
        subAgentProvider.addResponse(simpleResponse("sub-agent internal result"));

        var parentProvider = new MockLLMProvider();
        parentProvider.addResponse(CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.TOOL_CALLS,
                        Message.of(RoleType.ASSISTANT, "", null, null,
                                List.of(FunctionCall.of("call_1", "function", "researcher",
                                        "{\"query\": \"do research\"}")))
                )),
                new Usage(10, 20, 30)
        ));
        parentProvider.addResponse(simpleResponse("Parent final answer"));

        var researcher = Agent.builder()
                .name("researcher")
                .description("Research agent")
                .systemPrompt("You are a researcher")
                .llmProvider(subAgentProvider)
                .build().toSubAgentToolCall();

        var parent = Agent.builder()
                .name("coordinator")
                .description("Coordinator")
                .systemPrompt("You are a coordinator")
                .llmProvider(parentProvider)
                .subAgents(List.of(researcher))
                .maxTurn(2)
                .build();

        var result = parent.run("coordinate research");
        assertNotNull(result);

        // Parent messages should NOT contain sub-agent's internal ASSISTANT messages
        var parentMessages = parent.getMessages();
        var subAgentInternalMessages = parentMessages.stream()
                .filter(m -> m.role == RoleType.ASSISTANT)
                .filter(m -> m.getTextContent() != null && m.getTextContent().contains("sub-agent internal result"))
                .count();
        assertEquals(0, subAgentInternalMessages, "sub-agent internal messages should not leak to parent");

        // Parent should still have the tool result (the summary returned by SubAgentToolCall)
        var toolResults = parentMessages.stream()
                .filter(m -> m.role == RoleType.TOOL)
                .count();
        assertTrue(toolResults > 0, "parent should have tool result from sub-agent");
    }

    @Test
    void subAgentUsesChildContext() {
        var subAgentProvider = new MockLLMProvider();
        subAgentProvider.addResponse(simpleResponse("result"));

        var researcher = Agent.builder()
                .name("researcher")
                .description("Research agent")
                .systemPrompt("You are a researcher")
                .llmProvider(subAgentProvider)
                .build();

        var toolCall = SubAgentToolCall.builder().subAgent(researcher).build();

        var parentContext = ExecutionContext.builder()
                .sessionId("parent-session")
                .userId("user1")
                .customVariable("parent_key", "parent_value")
                .build();

        var result = toolCall.execute("{\"query\": \"research topic\"}", parentContext);
        assertTrue(result.isCompleted());
    }

    @Test
    void subAgentResetsStateBetweenCalls() {
        var subAgentProvider = new MockLLMProvider();
        subAgentProvider.addResponse(simpleResponse("first result"));
        subAgentProvider.addResponse(simpleResponse("second result"));

        var researcher = Agent.builder()
                .name("researcher")
                .description("Research agent")
                .systemPrompt("You are a researcher")
                .llmProvider(subAgentProvider)
                .build();

        var toolCall = SubAgentToolCall.builder().subAgent(researcher).build();
        var context = ExecutionContext.builder().sessionId("test").build();

        var result1 = toolCall.execute("{\"query\": \"first query\"}", context);
        assertTrue(result1.isCompleted());
        assertTrue(result1.getResult().contains("first result"));

        var result2 = toolCall.execute("{\"query\": \"second query\"}", context);
        assertTrue(result2.isCompleted());
        assertTrue(result2.getResult().contains("second result"));
    }

    @Test
    void childContextInheritsUserIdAndPersistence() {
        var parentContext = ExecutionContext.builder()
                .sessionId("parent")
                .userId("user123")
                .build();

        var child = parentContext.createChildContext("child-session");

        assertEquals("child-session", child.getSessionId());
        assertEquals("user123", child.getUserId());
        // Custom variables should NOT be inherited
        assertEquals(0, child.getCustomVariables().size());
    }

    @Test
    void tokenCostStillBubblesUpDespiteMessageIsolation() {
        var subAgentProvider = new MockLLMProvider();
        subAgentProvider.addResponse(simpleResponse("result"));

        var parentProvider = new MockLLMProvider();
        parentProvider.addResponse(CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.TOOL_CALLS,
                        Message.of(RoleType.ASSISTANT, "", null, null,
                                List.of(FunctionCall.of("call_1", "function", "researcher",
                                        "{\"query\": \"work\"}")))
                )),
                new Usage(100, 200, 300)
        ));
        parentProvider.addResponse(simpleResponse("done"));

        var researcher = Agent.builder()
                .name("researcher")
                .description("Research agent")
                .systemPrompt("You are a researcher")
                .llmProvider(subAgentProvider)
                .build().toSubAgentToolCall();

        var parent = Agent.builder()
                .name("coordinator")
                .description("Coordinator")
                .systemPrompt("You are a coordinator")
                .llmProvider(parentProvider)
                .subAgents(List.of(researcher))
                .maxTurn(2)
                .build();

        parent.run("do work");

        // Token cost includes both parent and sub-agent usage
        var totalTokens = parent.getCurrentTokenUsage().getTotalTokens();
        assertTrue(totalTokens > 300, "total tokens should include sub-agent costs, got: " + totalTokens);
    }
}
