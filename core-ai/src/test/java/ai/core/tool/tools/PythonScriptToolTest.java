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
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        String result = pythonScriptTool.execute(jsonArgs).getResult();
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
        String result = pythonScriptTool.execute(jsonArgs).getResult();
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
        String result = pythonScriptTool.execute(jsonArgs).getResult();
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
        String result = pythonScriptTool.execute(jsonArgs).getResult();
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
        String result = pythonScriptTool.execute(jsonArgs).getResult();
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
        String result = pythonScriptTool.execute(jsonArgs).getResult();
        logger.info("Result length: {}", result.length());

        assertNotNull(result, "Result should not be null");
        // Empty script should return empty string, not error
        assertTrue(result.isEmpty() || result.isBlank(),
            "Empty script should return empty output");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testScriptPathExecution(@TempDir Path tempDir) throws IOException {
        Path scriptFile = tempDir.resolve("test_script.py");
        Files.writeString(scriptFile, "print('Hello from script file')");

        String jsonArgs = String.format("{\"script_path\":\"%s\"}", scriptFile.toAbsolutePath().toString().replace("\\", "\\\\"));

        logger.info("Testing script path execution: {}", jsonArgs);
        var toolResult = pythonScriptTool.execute(jsonArgs);
        String result = toolResult.getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Hello from script file"), "Result should contain output from script file");
    }

    @Test
    void testScriptPathNotExists() {
        String jsonArgs = "{\"script_path\":\"/nonexistent/path/to/script.py\"}";

        logger.info("Testing script path not exists: {}", jsonArgs);
        var toolResult = pythonScriptTool.execute(jsonArgs);
        String result = toolResult.getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("does not exist"), "Result should indicate file does not exist");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testAsyncExecution() throws InterruptedException {
        String pythonCode = "import time\\ntime.sleep(0.1)\\nprint('Async done')";
        String jsonArgs = String.format("{\"code\":\"%s\",\"async\":true}", pythonCode);

        logger.info("Testing async execution: {}", jsonArgs);
        var toolResult = pythonScriptTool.execute(jsonArgs, ExecutionContext.empty());
        logger.info("Initial result: {}", toolResult);

        assertEquals(ToolCallResult.Status.PENDING, toolResult.getStatus(), "Async execution should return PENDING status");
        assertNotNull(toolResult.getTaskId(), "Task ID should not be null");

        String taskId = toolResult.getTaskId();
        logger.info("Task ID: {}", taskId);

        Thread.sleep(500);

        var pollResult = pythonScriptTool.poll(taskId);
        logger.info("Poll result: {}", pollResult);

        assertTrue(pollResult.isCompleted() || pollResult.isPending(), "Poll result should be COMPLETED or PENDING");

        if (pollResult.isCompleted()) {
            assertTrue(pollResult.getResult().contains("Async done"), "Result should contain async output");
        }
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testAsyncCancellation() {
        String pythonCode = "import time\\ntime.sleep(10)\\nprint('This should not print')";
        String jsonArgs = String.format("{\"code\":\"%s\",\"async\":true}", pythonCode);

        logger.info("Testing async cancellation: {}", jsonArgs);
        var toolResult = pythonScriptTool.execute(jsonArgs, ExecutionContext.empty());

        assertEquals(ToolCallResult.Status.PENDING, toolResult.getStatus(), "Async execution should return PENDING status");
        String taskId = toolResult.getTaskId();

        var cancelResult = pythonScriptTool.cancel(taskId);
        logger.info("Cancel result: {}", cancelResult);

        assertTrue(cancelResult.isCompleted(), "Cancel should return COMPLETED status");
        assertTrue(cancelResult.getResult().contains("cancelled"), "Cancel result should indicate task was cancelled");

        var pollAfterCancel = pythonScriptTool.poll(taskId);
        assertTrue(pollAfterCancel.isFailed(), "Poll after cancel should return FAILED (task not found)");
    }

    @Test
    void testPollNonexistentTask() {
        var pollResult = pythonScriptTool.poll("nonexistent-task-id");
        logger.info("Poll nonexistent task result: {}", pollResult);

        assertTrue(pollResult.isFailed(), "Poll should fail for nonexistent task");
        assertTrue(pollResult.getResult().contains("not found"), "Result should indicate task not found");
    }

    @Test
    @EnabledIf("isPythonAvailable")
    void testCodeTakesPrecedenceOverScriptPath(@TempDir Path tempDir) throws IOException {
        Path scriptFile = tempDir.resolve("test_script.py");
        Files.writeString(scriptFile, "print('From script file')");

        String code = "print('From code parameter')";
        String jsonArgs = String.format("{\"code\":\"%s\",\"script_path\":\"%s\"}",
            code, scriptFile.toAbsolutePath().toString().replace("\\", "\\\\"));

        logger.info("Testing code takes precedence: {}", jsonArgs);
        var toolResult = pythonScriptTool.execute(jsonArgs);
        String result = toolResult.getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("From code parameter"), "Code parameter should take precedence over script_path");
    }
}
