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
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ShellCommandToolTest {
    private final Logger logger = LoggerFactory.getLogger(ShellCommandToolTest.class);
    private MockLLMProvider mockLLMProvider;
    private ShellCommandTool shellCommandTool;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        // Create mock LLM provider
        mockLLMProvider = new MockLLMProvider();

        // Create shell command tool
        shellCommandTool = ShellCommandTool.builder().build();

        // Create some test files in temp directory for ls command to list
        Files.createFile(tempDir.resolve("test1.txt"));
        Files.createFile(tempDir.resolve("test2.txt"));
        Files.createFile(tempDir.resolve("readme.md"));
    }

    @Test
    void testListDirectoryCommand() {
        // First call: LLM decides to call shell_command tool to execute ls
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String lsCommand = System.getProperty("os.name").toLowerCase(Locale.getDefault()).contains("win") ? "dir" : "ls";

        FunctionCall toolCall = FunctionCall.of(
            "call_shell_001",
            "function",
            "run_bash_command",
            String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
                workspaceDir.replace("\\", "\\\\"), lsCommand)
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

        // Second call: LLM returns final answer after executing command
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "I have listed the files in the directory. The directory contains: test1.txt, test2.txt, and readme.md."
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(150, 30, 180)
        );

        // Add mock responses
        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        // Create agent with shell command tool
        var agent = Agent.builder()
            .name("shell-agent")
            .description("An agent that can execute shell commands")
            .systemPrompt("You are a helpful assistant that can execute shell commands to help users manage files and directories.")
            .toolCalls(List.of(shellCommandTool))
            .llmProvider(mockLLMProvider)
            .build();

        // Test listing directory
        String query = String.format("List the files in the directory: %s", workspaceDir);
        logger.info("Testing query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("test1.txt") || result.contains("test2.txt") || result.contains("readme.md") || result.contains("listed"),
            "Result should contain information about the files or indicate listing was performed");
    }

    @Test
    void testListDirectoryWithSpecificPath() {
        // First call: LLM decides to execute ls in the temp directory
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String detailedListCommand = System.getProperty("os.name").toLowerCase(Locale.getDefault()).contains("win") ? "dir" : "ls -la";

        FunctionCall toolCall = FunctionCall.of(
            "call_shell_002",
            "function",
            "run_bash_command",
            String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
                workspaceDir.replace("\\", "\\\\"), detailedListCommand)
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
            new Usage(110, 25, 135)
        );

        // Second call: LLM summarizes the results
        Message finalMessage = Message.of(
            RoleType.ASSISTANT,
            "The directory contains 3 files with detailed information including permissions and timestamps."
        );

        CompletionResponse finalResponse = CompletionResponse.of(
            List.of(Choice.of(FinishReason.STOP, finalMessage)),
            new Usage(160, 35, 195)
        );

        mockLLMProvider.addResponse(toolCallResponse);
        mockLLMProvider.addResponse(finalResponse);

        var agent = Agent.builder()
            .name("shell-agent")
            .description("An agent that can execute shell commands")
            .systemPrompt("You are a helpful assistant that can execute shell commands.")
            .toolCalls(List.of(shellCommandTool))
            .llmProvider(mockLLMProvider)
            .build();

        String query = String.format("Show me detailed information about files in %s", workspaceDir);
        logger.info("Testing query: {}", query);
        String result = agent.run(query, ExecutionContext.empty());
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("files") || result.contains("directory") || result.contains("3"),
            "Result should mention files or directory information");
    }

    @Test
    void testDirectToolExecution() {
        // Test tool execution directly without agent
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String command = System.getProperty("os.name").toLowerCase(Locale.getDefault()).contains("win") ? "dir" : "ls";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"), command);

        logger.info("Testing direct tool call with args: {}", jsonArgs);
        String result = shellCommandTool.execute(jsonArgs).getResult();
        logger.info("Direct execution result: {}", result);

        assertNotNull(result, "Result should not be null");
        // The result should contain file names from our temp directory
        assertTrue(result.contains("test1.txt") || result.contains("test2.txt") || result.contains("readme.md"),
            "Result should contain the file names we created");
    }

    @Test
    void testInvalidWorkspaceDirectory() {
        // Test with non-existent directory
        String nonExistentDir = tempDir.resolve("non_existent_directory").toAbsolutePath().toString();
        String command = System.getProperty("os.name").toLowerCase(Locale.getDefault()).contains("win") ? "dir" : "ls";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            nonExistentDir.replace("\\", "\\\\"), command);

        logger.info("Testing with invalid directory: {}", jsonArgs);
        String result = shellCommandTool.execute(jsonArgs).getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("does not exist"),
            "Result should indicate directory does not exist");
    }

    @Test
    void testCommandWithSpaces() throws IOException {
        // Create a file with spaces in the name
        Files.createFile(tempDir.resolve("file with spaces.txt"));

        String workspaceDir = tempDir.toAbsolutePath().toString();
        // Test that commands with complex arguments work correctly
        // Use dir/ls to list all files which should include our file with spaces
        String command = System.getProperty("os.name").toLowerCase(Locale.getDefault()).contains("win")
            ? "dir"
            : "ls -la";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"), command);

        logger.info("Testing command output with spaces in filename: {}", jsonArgs);
        String result = shellCommandTool.execute(jsonArgs).getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        // Verify the file with spaces in its name appears in the directory listing
        assertTrue(result.contains("with spaces") || result.contains("file"),
            "Result should list file names with spaces correctly");
    }

    @Test
    void testEmptyOutputCommand() {
        // Test command that produces no output (create a file in Windows, touch in Unix)
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String command = System.getProperty("os.name").toLowerCase(Locale.getDefault()).contains("win")
            ? "New-Item -ItemType File -Path \"test_empty.txt\" -Force | Out-Null"
            : "touch test_empty.txt";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"), command.replace("\"", "\\\""));

        logger.info("Testing command with empty output: {}", jsonArgs);
        String result = shellCommandTool.execute(jsonArgs).getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        // Empty output should return empty string, not null
        assertTrue(result.isEmpty() || result.isBlank(),
            "Empty output should return empty string");
    }

    @Test
    void testMissingCommandParameter() {
        // Test with missing command parameter
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String jsonArgs = String.format("{\"workspace_dir\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"));

        logger.info("Testing with missing command parameter: {}", jsonArgs);
        String result = shellCommandTool.execute(jsonArgs).getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate command parameter is required");
    }

    @Test
    void testScriptPathExecution() throws IOException {
        String scriptContent = isWindows()
            ? "@echo off\necho HelloFromScriptFile"
            : "#!/bin/bash\necho 'HelloFromScriptFile'";
        String scriptExt = isWindows() ? ".bat" : ".sh";
        Path scriptFile = tempDir.resolve("test_script" + scriptExt);
        Files.writeString(scriptFile, scriptContent);

        if (!isWindows()) {
            scriptFile.toFile().setExecutable(true);
        }

        String jsonArgs = String.format("{\"script_path\":\"%s\"}",
            scriptFile.toAbsolutePath().toString().replace("\\", "\\\\"));

        logger.info("Testing script path execution: {}", jsonArgs);
        var toolResult = shellCommandTool.execute(jsonArgs, ExecutionContext.empty());
        String result = toolResult.getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("HelloFromScriptFile"), "Result should contain output from script file");
    }

    @Test
    void testScriptPathNotExists() {
        String jsonArgs = "{\"script_path\":\"/nonexistent/path/to/script.sh\"}";

        logger.info("Testing script path not exists: {}", jsonArgs);
        var toolResult = shellCommandTool.execute(jsonArgs, ExecutionContext.empty());
        String result = toolResult.getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("does not exist"), "Result should indicate file does not exist");
    }

    @Test
    void testAsyncExecution() throws InterruptedException {
        String command = isWindows() ? "echo Async done" : "echo 'Async done'";
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\",\"async\":true}",
            workspaceDir.replace("\\", "\\\\"), command);

        logger.info("Testing async execution: {}", jsonArgs);
        var toolResult = shellCommandTool.execute(jsonArgs, ExecutionContext.empty());
        logger.info("Initial result: {}", toolResult);

        assertEquals(ToolCallResult.Status.PENDING, toolResult.getStatus(), "Async execution should return PENDING status");
        assertNotNull(toolResult.getTaskId(), "Task ID should not be null");

        String taskId = toolResult.getTaskId();
        logger.info("Task ID: {}", taskId);

        // Wait for task to complete
        ToolCallResult pollResult = null;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(500);
            pollResult = shellCommandTool.poll(taskId);
            logger.info("Poll result (attempt {}): {}", i + 1, pollResult);
            if (pollResult.isCompleted() || pollResult.isFailed()) {
                break;
            }
        }

        assertNotNull(pollResult, "Poll result should not be null");
        assertTrue(pollResult.isCompleted(), "Poll result should be COMPLETED");
        assertTrue(pollResult.getResult().contains("Async") || pollResult.getResult().contains("done"), "Result should contain async output");
    }

    @Test
    void testAsyncCancellation() {
        String command = isWindows() ? "ping -n 100 127.0.0.1" : "sleep 100";
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\",\"async\":true}",
            workspaceDir.replace("\\", "\\\\"), command);

        logger.info("Testing async cancellation: {}", jsonArgs);
        var toolResult = shellCommandTool.execute(jsonArgs, ExecutionContext.empty());

        assertEquals(ToolCallResult.Status.PENDING, toolResult.getStatus(), "Async execution should return PENDING status");
        String taskId = toolResult.getTaskId();

        var cancelResult = shellCommandTool.cancel(taskId);
        logger.info("Cancel result: {}", cancelResult);

        assertTrue(cancelResult.isCompleted(), "Cancel should return COMPLETED status");
        assertTrue(cancelResult.getResult().contains("cancelled"), "Cancel result should indicate task was cancelled");

        var pollAfterCancel = shellCommandTool.poll(taskId);
        assertTrue(pollAfterCancel.isFailed(), "Poll after cancel should return FAILED (task not found)");
    }

    @Test
    void testPollNonexistentTask() {
        var pollResult = shellCommandTool.poll("nonexistent-task-id");
        logger.info("Poll nonexistent task result: {}", pollResult);

        assertTrue(pollResult.isFailed(), "Poll should fail for nonexistent task");
        assertTrue(pollResult.getResult().contains("not found"), "Result should indicate task not found");
    }

    @Test
    void testCommandTakesPrecedenceOverScriptPath() throws IOException {
        String scriptContent = isWindows()
            ? "@echo off\necho FromScriptFile"
            : "#!/bin/bash\necho 'FromScriptFile'";
        String scriptExt = isWindows() ? ".bat" : ".sh";
        Path scriptFile = tempDir.resolve("precedence_test" + scriptExt);
        Files.writeString(scriptFile, scriptContent);

        if (!isWindows()) {
            scriptFile.toFile().setExecutable(true);
        }

        String command = isWindows() ? "echo FromCommandParameter" : "echo 'FromCommandParameter'";
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\",\"script_path\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"),
            command,
            scriptFile.toAbsolutePath().toString().replace("\\", "\\\\"));

        logger.info("Testing command takes precedence: {}", jsonArgs);
        var toolResult = shellCommandTool.execute(jsonArgs, ExecutionContext.empty());
        String result = toolResult.getResult();
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("FromCommandParameter"), "Command parameter should take precedence over script_path");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.getDefault()).contains("win");
    }
}
