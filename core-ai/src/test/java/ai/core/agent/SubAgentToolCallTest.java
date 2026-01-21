package ai.core.agent;

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
import ai.core.llm.providers.LiteLLMProvider;
import ai.core.tool.tools.SubAgentToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class SubAgentToolCallTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubAgentToolCallTest.class);

    LLMProvider llmProvider;
    LLMProvider subAgentLlmProvider;

    @BeforeEach
    void setUp() {
        llmProvider = Mockito.mock(LiteLLMProvider.class);
        subAgentLlmProvider = Mockito.mock(LiteLLMProvider.class);
    }

    @Test
    void testSubAgentToolCallCreation() {
        // Create a subagent
        var subAgent = Agent.builder()
                .name("researcher")
                .description("Expert at researching topics")
                .systemPrompt("You are a research specialist")
                .llmProvider(subAgentLlmProvider)
                .build();

        // Create SubAgentToolCall
        var toolCall = SubAgentToolCall.builder().subAgent(subAgent).build();

        assertEquals("researcher", toolCall.getName());
        assertEquals("Expert at researching topics", toolCall.getDescription());
        assertTrue(toolCall.isSubAgent());
        assertNotNull(toolCall.getParameters());
        assertEquals(1, toolCall.getParameters().size());
        assertEquals("query", toolCall.getParameters().getFirst().getName());
    }

    @Test
    void testAgentWithSubAgents() {
        // Create subagents
        var researcher = Agent.builder()
                .name("researcher")
                .description("Expert at researching topics")
                .systemPrompt("You are a research specialist")
                .llmProvider(subAgentLlmProvider)
                .build().toSubAgentToolCall();

        var writer = Agent.builder()
                .name("writer")
                .description("Expert at writing content")
                .systemPrompt("You are a professional writer")
                .llmProvider(subAgentLlmProvider)
                .build().toSubAgentToolCall();

        // Create coordinator with subagents
        var coordinator = Agent.builder()
                .name("coordinator")
                .description("Coordinates research and writing")
                .systemPrompt("You are a team coordinator")
                .llmProvider(llmProvider)
                .subAgents(List.of(researcher, writer))
                .build();

        // Verify subagents are added
        assertTrue(coordinator.hasSubAgents());
        assertEquals(2, coordinator.getSubAgents().size());

        // Verify subagents are converted to tools
        assertEquals(2, coordinator.getToolCalls().size());

        // Verify parent-child relationship
        assertEquals(coordinator, researcher.getSubAgent().getParentNode());
        assertEquals(coordinator, writer.getSubAgent().getParentNode());
    }

    @Test
    void testSubAgentExecution() {
        // Mock subagent response
        var subAgentResponse = CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.STOP,
                        Message.of(RoleType.ASSISTANT, "Research result: Climate change impacts agriculture significantly.")
                )),
                new Usage(50, 30, 80)
        );
        when(subAgentLlmProvider.completionStream(any(CompletionRequest.class), any(StreamingCallback.class)))
                .thenReturn(subAgentResponse);

        // Create subagent
        var researcher = Agent.builder()
                .name("researcher")
                .description("Expert at researching topics")
                .systemPrompt("You are a research specialist")
                .llmProvider(subAgentLlmProvider)
                .build();

        // Create SubAgentToolCall and execute
        var toolCall = SubAgentToolCall.builder().subAgent(researcher).build();
        var result = toolCall.execute("{\"query\": \"Research climate change\"}", ExecutionContext.empty());

        assertTrue(result.isCompleted());
        assertNotNull(result.getResult());
        assertTrue(result.getResult().contains("Research result"));
        assertEquals("researcher", result.getStats().get("subagent_name"));
    }

    @Test
    void testCoordinatorCallsSubAgent() {
        // Mock subagent response
        var subAgentResponse = CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.STOP,
                        Message.of(RoleType.ASSISTANT, "Research complete: Found 5 relevant papers.")
                )),
                new Usage(50, 30, 80)
        );
        when(subAgentLlmProvider.completionStream(any(CompletionRequest.class), any(StreamingCallback.class)))
                .thenReturn(subAgentResponse);

        // Mock coordinator response - it decides to call the researcher subagent
        var coordinatorToolCallResponse = CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.TOOL_CALLS,
                        Message.of(RoleType.ASSISTANT, "", null, null, null,
                                List.of(FunctionCall.of("call_1", "function", "researcher",
                                        "{\"query\": \"Research climate change effects\"}")))
                )),
                new Usage(20, 10, 30)
        );

        var coordinatorFinalResponse = CompletionResponse.of(
                List.of(Choice.of(
                        FinishReason.STOP,
                        Message.of(RoleType.ASSISTANT, "Based on the research, here is my summary...")
                )),
                new Usage(30, 20, 50)
        );

        when(llmProvider.completionStream(any(CompletionRequest.class), any(StreamingCallback.class)))
                .thenReturn(coordinatorToolCallResponse)
                .thenReturn(coordinatorFinalResponse);

        // Create subagent
        var researcher = Agent.builder()
                .name("researcher")
                .description("Expert at researching topics")
                .systemPrompt("You are a research specialist")
                .llmProvider(subAgentLlmProvider)
                .build().toSubAgentToolCall();

        // Create coordinator
        var coordinator = Agent.builder()
                .name("coordinator")
                .description("Coordinates tasks")
                .systemPrompt("You are a coordinator with access to a researcher agent")
                .llmProvider(llmProvider)
                .subAgents(List.of(researcher))
                .maxTurn(2)
                .build();

        // Execute
        var result = coordinator.run("Research and summarize climate change effects");

        assertNotNull(result);
        LOGGER.info("Coordinator result: {}", result);
    }

    @Test
    void testNestedSubAgents() {
        // Create leaf agents
        var coder = Agent.builder()
                .name("coder")
                .description("Writes code")
                .llmProvider(subAgentLlmProvider)
                .build().toSubAgentToolCall();

        var reviewer = Agent.builder()
                .name("reviewer")
                .description("Reviews code")
                .llmProvider(subAgentLlmProvider)
                .build().toSubAgentToolCall();

        // Create dev team with subagents
        var devTeam = Agent.builder()
                .name("dev-team")
                .description("Development team")
                .llmProvider(llmProvider)
                .subAgents(List.of(coder, reviewer))
                .build().toSubAgentToolCall();

        // Create project manager with dev team as subagent
        var projectManager = Agent.builder()
                .name("project-manager")
                .description("Manages projects")
                .llmProvider(llmProvider)
                .subAgents(List.of(devTeam))
                .build();

        // Verify nested structure
        assertTrue(projectManager.hasSubAgents());
        assertEquals(1, projectManager.getSubAgents().size());
    }

    @Test
    void testSubAgentWithEmptyQuery() {
        var subAgent = Agent.builder()
                .name("test-agent")
                .description("Test agent")
                .llmProvider(subAgentLlmProvider)
                .build();

        var toolCall = SubAgentToolCall.builder().subAgent(subAgent).build();

        // Empty query should fail
        var result = toolCall.execute("{\"query\": \"\"}", ExecutionContext.empty());
        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("required"));

        // Missing query should fail
        var result2 = toolCall.execute("{}", ExecutionContext.empty());
        assertTrue(result2.isFailed());
    }
}
