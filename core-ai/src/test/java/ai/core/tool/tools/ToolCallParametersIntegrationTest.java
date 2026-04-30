package ai.core.tool.tools;

import ai.core.agent.Agent;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.providers.MockLLMProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for ToolCallParameters with PythonScriptTool
 * Tests that LLM-generated JSON strings can be correctly parsed and passed to tools
 *
 * @author stephen
 */
class ToolCallParametersIntegrationTest {
    private final Logger logger = LoggerFactory.getLogger(ToolCallParametersIntegrationTest.class);
    private MockLLMProvider mockLLMProvider;
    private PythonScriptTool pythonScriptTool;

    @BeforeEach
    void setUp() {
        mockLLMProvider = new MockLLMProvider();

        pythonScriptTool = PythonScriptTool.builder().build();
    }

    /**
     * Check if Python is available in the system
     */
    boolean isPythonAvailable() {
        try {
            var process = new ProcessBuilder("python", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testPythonScriptToolWithLLMFunctionCall() {
        logger.info("Testing Python script tool with LLM function call");

        // Mock LLM response with tool call
        var toolCall = FunctionCall.of(
            "call_python_001",
            "function",
            "run_python_script",
            "{\"code\":\"print('Hello from LLM')\"}"
        );

        var toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
            null,
            List.of(toolCall)
        );

        var toolCallResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.TOOL_CALLS, toolCallMessage)),
            new Usage(100, 20, 120)
        );

        // Final response after tool execution
        var finalMessage = Message.of(
            RoleType.ASSISTANT,
            "The Python script executed successfully and printed: Hello from LLM"
        );

        var finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        // Add mock responses
        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        // Create agent with Python script tool
        var agent = Agent.builder()
            .name("test-agent")
            .llmProvider(mockLLMProvider)
            .systemPrompt("You are a helpful assistant that can execute Python scripts.")
            .toolCalls(List.of(pythonScriptTool))
            .build();

        // Execute agent
        var result = agent.run("Execute a Python script that prints 'Hello from LLM'", ai.core.agent.ExecutionContext.empty());

        // Verify
        assertNotNull(result);
        logger.info("Agent output: {}", result);
        assertTrue(result.contains("Hello from LLM") || result.contains("executed"), "Output should contain the expected message");
    }

    @Test
    void testToolCallParametersOfMethod() {
        logger.info("Testing ToolCallParameters.of() method");

        // Test ToolCallParameters.of(String.class) directly
        var params = ai.core.tool.ToolCallParameters.of(String.class);
        assertNotNull(params);
        assertEquals(1, params.size(), "Should have 1 parameter for String.class");
        var param = params.get(0);
        assertEquals("string", param.getName());
        assertEquals(String.class, param.getClassType());

        logger.info("ToolCallParameters.of() method verified successfully");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testDirectToolCallWithJsonString() {
        logger.info("Testing direct tool call with JSON string");

        // Test Python script tool with direct JSON string (simulating LLM output)
        var pythonJsonArgs = "{\"code\":\"print(2 + 3)\"}";
        var pythonResult = pythonScriptTool.execute(pythonJsonArgs).getResult();

        assertNotNull(pythonResult);
        assertTrue(pythonResult.contains("5"), "Python script should output 5");
        logger.info("Python tool result: {}", pythonResult);

    }

    @Test
    void testInvalidJsonStringHandling() {
        logger.info("Testing invalid JSON string handling");

        // Test with invalid JSON (missing quotes around keys)
        var invalidJson = "{invalid: \"value\"}";
        var pythonResult = pythonScriptTool.execute(invalidJson).getResult();
        assertTrue(pythonResult.contains("Failed to parse") || pythonResult.contains("parameter is required"),
            "Should return parse error or parameter required error");

        logger.info("Invalid JSON handling verified successfully");
    }
}
