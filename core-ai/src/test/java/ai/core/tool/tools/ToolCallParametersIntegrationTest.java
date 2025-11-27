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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for ToolCallParameters with ShellCommandTool and PythonScriptTool
 * Tests that LLM-generated JSON strings can be correctly parsed and passed to tools
 *
 * @author stephen
 */
class ToolCallParametersIntegrationTest {
    private final Logger logger = LoggerFactory.getLogger(ToolCallParametersIntegrationTest.class);
    private MockLLMProvider mockLLMProvider;
    private PythonScriptTool pythonScriptTool;
    private ShellCommandTool shellCommandTool;

    @BeforeEach
    void setUp() {
        mockLLMProvider = new MockLLMProvider();

        // Create Python script tool with ToolCallParameters.of(String.class)
        pythonScriptTool = PythonScriptTool.builder().build();

        // Create Shell command tool with ToolCallParameters.of(String.class)
        shellCommandTool = ShellCommandTool.builder().build();
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
    void testShellCommandToolWithLLMFunctionCall() {
        logger.info("Testing Shell command tool with LLM function call");

        // Create a temporary directory for testing
        var tempDir = core.framework.util.Files.tempDir().toAbsolutePath().toString();

        // Mock LLM response with tool call
        var toolCall = FunctionCall.of(
            "call_shell_001",
            "function",
            "run_bash_command",
            "{\"workspace_dir\":\"" + tempDir.replace("\\", "\\\\") + "\",\"command\":\"echo 'Test from LLM'\"}"
        );

        var toolCallMessage = Message.of(
            RoleType.ASSISTANT,
            "",
            null,
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
            "The shell command executed successfully."
        );

        var finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        // Add mock responses
        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        // Create agent with Shell command tool
        var agent = Agent.builder()
            .name("test-agent")
            .llmProvider(mockLLMProvider)
            .systemPrompt("You are a helpful assistant that can execute shell commands.")
            .toolCalls(List.of(shellCommandTool))
            .build();

        // Execute agent
        var result = agent.run("Execute a shell command that echoes 'Test from LLM'", ai.core.agent.ExecutionContext.empty());

        // Verify
        assertNotNull(result);
        logger.info("Agent output: {}", result);
        assertTrue(result.contains("successfully") || result.contains("executed"), "Output should indicate successful execution");
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
    void testPythonScriptToolParameterConfiguration() {
        logger.info("Testing PythonScriptTool parameter configuration");

        var tool = PythonScriptTool.builder().build();

        var params = tool.getParameters();
        assertNotNull(params);
        assertEquals(1, params.size(), "Python tool should have 1 parameter");

        var codeParam = params.get(0);
        assertEquals("code", codeParam.getName());
        assertEquals("python code", codeParam.getDescription());
        assertEquals(String.class, codeParam.getClassType());

        logger.info("PythonScriptTool parameters: name='{}', description='{}', type={}",
            codeParam.getName(), codeParam.getDescription(), codeParam.getClassType().getSimpleName());
    }

    @Test
    void testShellCommandToolParameterConfiguration() {
        logger.info("Testing ShellCommandTool parameter configuration");

        var tool = ShellCommandTool.builder().build();

        var params = tool.getParameters();
        assertNotNull(params);
        assertEquals(2, params.size(), "Shell tool should have 2 parameters");

        var workspaceDirParam = params.get(0);
        assertEquals("workspace_dir", workspaceDirParam.getName());
        assertEquals("dir of command to exec", workspaceDirParam.getDescription());
        assertEquals(String.class, workspaceDirParam.getClassType());

        var commandParam = params.get(1);
        assertEquals("command", commandParam.getName());
        assertEquals("command string", commandParam.getDescription());
        assertEquals(String.class, commandParam.getClassType());

        logger.info("ShellCommandTool parameters: [0] name='{}', description='{}'; [1] name='{}', description='{}'",
            workspaceDirParam.getName(), workspaceDirParam.getDescription(),
            commandParam.getName(), commandParam.getDescription());
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

        // Test Shell command tool with direct JSON string (simulating LLM output)
        var tempDir = core.framework.util.Files.tempDir().toAbsolutePath().toString();
        var shellJsonArgs = "{\"workspace_dir\":\"" + tempDir.replace("\\", "\\\\") + "\",\"command\":\"echo test\"}";
        var shellResult = shellCommandTool.execute(shellJsonArgs).getResult();

        assertNotNull(shellResult);
        assertFalse(shellResult.startsWith("Error:"), "Shell command should not return error");
        logger.info("Shell tool result: {}", shellResult);
    }

    @Test
    void testInvalidJsonStringHandling() {
        logger.info("Testing invalid JSON string handling");

        // Test with invalid JSON (missing quotes around keys)
        var invalidJson = "{invalid: \"value\"}";
        var pythonResult = pythonScriptTool.execute(invalidJson).getResult();
        assertTrue(pythonResult.contains("Failed to parse") || pythonResult.contains("parameter is required"),
            "Should return parse error or parameter required error");

        var shellResult = shellCommandTool.execute(invalidJson).getResult();
        assertTrue(shellResult.contains("Failed to parse") || shellResult.contains("parameter is required"),
            "Should return parse error or parameter required error");

        logger.info("Invalid JSON handling verified successfully");
    }

    @Test
    void testMissingParametersHandling() {
        logger.info("Testing missing parameters handling");

        // Test Python tool with missing 'code' parameter
        var pythonEmptyJson = "{}";
        var pythonResult = pythonScriptTool.execute(pythonEmptyJson).getResult();
        assertTrue(pythonResult.contains("code parameter is required"), "Should return parameter required error");

        // Test Shell tool with missing 'command' parameter
        var tempDir = core.framework.util.Files.tempDir().toAbsolutePath().toString();
        var shellMissingCmd = "{\"workspace_dir\":\"" + tempDir.replace("\\", "\\\\") + "\"}";
        var shellResult = shellCommandTool.execute(shellMissingCmd).getResult();
        assertTrue(shellResult.contains("command parameter is required"), "Should return parameter required error");

        logger.info("Missing parameters handling verified successfully");
    }
}
