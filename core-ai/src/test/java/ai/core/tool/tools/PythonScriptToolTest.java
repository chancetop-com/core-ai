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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for PythonScriptTool with mock LLM provider
 * This test verifies that the Python script tool can execute Python code correctly.
 *
 * @author stephen
 */
class PythonScriptToolTest {
    private final Logger logger = LoggerFactory.getLogger(PythonScriptToolTest.class);
    private MockLLMProvider mockLLMProvider;
    private PythonScriptTool pythonScriptTool;

    @BeforeEach
    void setUp() {
        // Create mock LLM provider
        mockLLMProvider = new MockLLMProvider();

        // Create Python script tool
        pythonScriptTool = PythonScriptTool.builder()
            .name("python_script")
            .description("Execute Python scripts")
            .build();
    }

    /**
     * Check if Python is available in the system
     */
    boolean isPythonAvailable() {
        try {
            var process = new ProcessBuilder("python", "--version").start();
            var exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testSimplePythonScript() {
        // First call: LLM decides to call python_script tool
        String pythonCode = "print('Hello from Python')";

        FunctionCall toolCall = FunctionCall.of(
            "call_python_001",
            "function",
            "run_python_script",
            String.format("{\"code\":\"%s\"}", pythonCode)
        );

        Message toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
            null,
            null,
            List.of(toolCall)
        );

        CompletionResponse toolCallResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.TOOL_CALLS, toolCallMessage)),
            new Usage(100, 20, 120)
        );

        // Second call: LLM returns final answer after script execution
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "The Python script executed successfully and printed: Hello from Python"
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        // Add mock responses
        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        // Create agent with Python script tool
        var agent = Agent.builder()
            .name("python-agent")
            .description("An agent that can execute Python scripts")
            .systemPrompt("You are a helpful assistant that can execute Python scripts.")
            .toolCalls(List.of(pythonScriptTool))
            .llmProvider(mockLLMProvider)
            .build();

        // Test script execution
        String query = "Execute a Python script that prints 'Hello from Python'";
        logger.info("Testing query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Hello") || result.contains("Python") || result.contains("executed"),
            "Result should contain information about the script execution");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testDirectToolExecution() {
        // Test tool execution directly without agent
        String pythonCode = "print(2 + 3)";

        String jsonArgs = String.format("{\"code\":\"%s\"}", pythonCode);

        logger.info("Testing direct tool call with args: {}", jsonArgs);
        String result = pythonScriptTool.call(jsonArgs);
        logger.info("Direct execution result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("5"),
            "Result should contain the calculation result");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testPythonScriptWithMultipleLines() {
        // Test multi-line Python script
        String pythonCode = "for i in range(3):\\n    print(f'Line {i}')";

        String jsonArgs = String.format("{\"code\":\"%s\"}", pythonCode);

        logger.info("Testing multi-line Python script: {}", jsonArgs);
        String result = pythonScriptTool.call(jsonArgs);
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Line"),
            "Result should contain output from the loop");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testPythonScriptWithError() {
        // Test Python script that causes an error
        String pythonCode = "print(undefined_variable)";

        String jsonArgs = String.format("{\"code\":\"%s\"}", pythonCode);

        logger.info("Testing Python script with error: {}", jsonArgs);
        String result = pythonScriptTool.call(jsonArgs);
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("NameError") || result.contains("error") || result.contains("exited"),
            "Result should indicate an error occurred");
    }

    @Test
    void testMissingCodeParameter() {
        // Test with missing code parameter
        String jsonArgs = "{}";

        logger.info("Testing with missing code parameter: {}", jsonArgs);
        String result = pythonScriptTool.call(jsonArgs);
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate code parameter is required");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testPythonScriptWithImports() {
        // Test Python script with imports
        String pythonCode = "import json\\ndata = {'key': 'value'}\\nprint(json.dumps(data))";

        String jsonArgs = String.format("{\"code\":\"%s\"}", pythonCode);

        logger.info("Testing Python script with imports: {}", jsonArgs);
        String result = pythonScriptTool.call(jsonArgs);
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("key") || result.contains("value"),
            "Result should contain JSON output");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testEmptyPythonScript() {
        // Test empty Python script (should return empty output)
        String pythonCode = "# This is just a comment";

        String jsonArgs = String.format("{\"code\":\"%s\"}", pythonCode);

        logger.info("Testing empty Python script: {}", jsonArgs);
        String result = pythonScriptTool.call(jsonArgs);
        logger.info("Result length: {}", result.length());

        assertNotNull(result, "Result should not be null");
        // Empty script should return empty string, not error
        assertTrue(result.isEmpty() || result.isBlank(),
            "Empty script should return empty output");
    }
}
