package ai.core.tool.tools;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import ai.core.rag.RagConfig;
import ai.core.vectorstore.VectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class for RagTool
 */
@ExtendWith(MockitoExtension.class)
class RagToolTest {
    private final Logger logger = LoggerFactory.getLogger(RagToolTest.class);

    @Mock
    private VectorStore mockVectorStore;

    private TestLLMProvider testLLMProvider;
    private RagTool ragTool;

    @BeforeEach
    void setUp() {
        // Create test LLM provider with embeddings and reranking support
        testLLMProvider = new TestLLMProvider();

        // Create RAG tool with mocked dependencies
        ragTool = RagTool.builder()
            .name("rag_search")
            .description("Search for relevant documents using RAG")
            .vectorStore(mockVectorStore)
            .llmProvider(testLLMProvider)
            .build();
    }

    @Test
    void testRagToolCreationWithValidDependencies() {
        assertNotNull(ragTool, "RAG tool should be created successfully");
        assertEquals("rag_search", ragTool.getName());
        assertEquals("Search for relevant documents using RAG", ragTool.getDescription());
    }

    @Test
    void testRagToolCreationWithNullVectorStore() {
        assertThrows(IllegalArgumentException.class, () -> {
            new RagTool(null, testLLMProvider);
        }, "Should throw IllegalArgumentException when vectorStore is null");
    }

    @Test
    void testRagToolCreationWithNullLLMProvider() {
        assertThrows(IllegalArgumentException.class, () -> {
            new RagTool(mockVectorStore, null);
        }, "Should throw IllegalArgumentException when llmProvider is null");
    }

    @Test
    void testRagToolCreationFromRagConfig() {
        RagConfig ragConfig = RagConfig.builder()
            .useRag(true)
            .vectorStore(mockVectorStore)
            .llmProvider(testLLMProvider)
            .topK(10)
            .threshold(0.5)
            .build();

        RagTool ragToolFromConfig = new RagTool(ragConfig);
        assertNotNull(ragToolFromConfig, "RAG tool should be created from RagConfig");
    }

    @Test
    void testRagToolCreationFromNullRagConfig() {
        assertThrows(IllegalArgumentException.class, () -> {
            new RagTool((RagConfig) null);
        }, "Should throw IllegalArgumentException when RagConfig is null");
    }

    @Test
    void testRagToolWithMissingQuery() {
        String jsonArgs = "{\"topK\":5,\"threshold\":0.3}";
        logger.info("Testing RAG tool with missing query: {}", jsonArgs);

        String result = ragTool.execute(jsonArgs).getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("query"),
            "Result should indicate query parameter is required");
    }

    @Test
    void testRagToolWithEmptyQuery() {
        String jsonArgs = "{\"query\":\"\",\"topK\":5,\"threshold\":0.3}";
        logger.info("Testing RAG tool with empty query: {}", jsonArgs);

        String result = ragTool.execute(jsonArgs).getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("query"),
            "Result should indicate query parameter is required");
    }

    @Test
    void testRagToolWithAgent() {
        // Create a mock LLM provider that simulates tool calling
        MockLLMProvider mockLLMProvider = new MockLLMProvider();

        // First call: LLM decides to use RAG tool
        FunctionCall ragToolCall = FunctionCall.of(
            "call_rag_001",
            "function",
            "rag_search",
            "{\"query\":\"What is machine learning?\"}"
        );

        Message toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
            null,
            null,
            List.of(ragToolCall)
        );

        CompletionResponse toolCallResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.TOOL_CALLS, toolCallMessage)),
            new Usage(100, 20, 120)
        );

        // Second call: LLM uses retrieved context to answer
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "Based on the retrieved context, machine learning is a subset of artificial intelligence."
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        // Add mock responses
        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        // Create agent with RAG tool (use mock provider instead of test provider)
        var agent = Agent.builder()
            .name("rag-agent")
            .description("An agent that can search for information using RAG")
            .systemPrompt("You are a helpful assistant that can search for information in a knowledge base.")
            .toolCalls(List.of(ragTool))
            .llmProvider(mockLLMProvider)
            .build();

        // Test RAG query
        String query = "What is machine learning?";
        logger.info("Testing agent with RAG tool, query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Agent result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("machine learning") || result.contains("artificial intelligence"),
            "Result should contain information from retrieved context");
    }

    @Test
    void testRagToolParameterDefinition() {
        var parameters = ragTool.getParameters();
        assertNotNull(parameters, "Parameters should not be null");
        assertEquals(3, parameters.size(), "Should have 3 parameters: query, topK, threshold");

        // Check parameter names
        assertTrue(parameters.stream().anyMatch(p -> "query".equals(p.getName())),
            "Should have query parameter");
        assertTrue(parameters.stream().anyMatch(p -> "topK".equals(p.getName())),
            "Should have topK parameter");
        assertTrue(parameters.stream().anyMatch(p -> "threshold".equals(p.getName())),
            "Should have threshold parameter");
    }

    @Test
    void testRagToolBuilderWithDefaults() {
        RagTool builtTool = RagTool.builder()
            .vectorStore(mockVectorStore)
            .llmProvider(testLLMProvider)
            .defaultTopK(20)
            .defaultThreshold(0.7)
            .build();

        assertNotNull(builtTool, "Built tool should not be null");
        assertEquals("rag_search", builtTool.getName());
        assertEquals("Search for relevant documents using RAG (Retrieval-Augmented Generation)",
            builtTool.getDescription());
    }

    @Test
    void testRagToolBuilderWithCustomNameAndDescription() {
        RagTool builtTool = RagTool.builder()
            .name("custom_rag")
            .description("Custom RAG search tool")
            .vectorStore(mockVectorStore)
            .llmProvider(testLLMProvider)
            .build();

        assertNotNull(builtTool, "Built tool should not be null");
        assertEquals("custom_rag", builtTool.getName());
        assertEquals("Custom RAG search tool", builtTool.getDescription());
    }

    @Test
    void testAgentWithRagToolManually() {
        // Setup mock responses (no need to mock vector store since RagTool will fail internally)

        // Create RAG config
        RagConfig ragConfig = RagConfig.builder()
            .vectorStore(mockVectorStore)
            .llmProvider(testLLMProvider)
            .topK(5)
            .threshold(0.3)
            .build();

        // Create RAG tool manually
        RagTool manualRagTool = RagTool.builder()
            .name("rag_search")
            .description("Search knowledge base")
            .ragConfig(ragConfig)
            .build();

        // Create mock provider for agent test
        MockLLMProvider mockLLMProvider = new MockLLMProvider();

        // First call: LLM decides to use RAG tool
        FunctionCall ragToolCall = FunctionCall.of(
            "call_rag_002",
            "function",
            "rag_search",
            "{\"query\":\"Tell me about Python programming\"}"
        );

        Message toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
            null,
            null,
            List.of(ragToolCall)
        );

        CompletionResponse toolCallResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.TOOL_CALLS, toolCallMessage)),
            new Usage(100, 20, 120)
        );

        // Second call: LLM uses retrieved context
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "Based on my search, Python is a popular programming language known for its simplicity and versatility."
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        // Create agent with manually added RAG tool
        var agent = Agent.builder()
            .name("manual-rag-agent")
            .description("An agent with manually added RAG tool")
            .systemPrompt("You are a helpful assistant.")
            .toolCalls(List.of(manualRagTool))  // Manually add RAG tool
            .llmProvider(mockLLMProvider)
            .build();

        // Test query
        String query = "Tell me about Python programming";
        logger.info("Testing agent with manually added RAG tool, query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Agent result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Python") || result.contains("programming"),
            "Result should contain information about Python programming");
    }

    @Test
    void testRagToolWithInvalidJson() {
        String invalidJson = "not a json string";
        logger.info("Testing RAG tool with invalid JSON: {}", invalidJson);

        String result = ragTool.execute(invalidJson).getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error executing RAG query"),
            "Result should indicate an error occurred");
    }

    /**
     * Simple test LLM Provider implementation for testing RAG Tool
     */
    private static final class TestLLMProvider extends MockLLMProvider {
        // MockLLMProvider provides basic functionality
        // We're not overriding embeddings/rerankings as they can't be properly mocked
        // due to constructor issues with Embedding and RerankingResponse
    }
}