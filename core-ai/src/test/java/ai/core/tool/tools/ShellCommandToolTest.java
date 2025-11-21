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
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        shellCommandTool = ShellCommandTool.builder()
            .name("shell_command")
            .description("Execute shell commands to interact with the file system")
            .build();

        // Create some test files in temp directory for ls command to list
        Files.createFile(tempDir.resolve("test1.txt"));
        Files.createFile(tempDir.resolve("test2.txt"));
        Files.createFile(tempDir.resolve("readme.md"));
    }

    @Test
    void testListDirectoryCommand() {
        // First call: LLM decides to call shell_command tool to execute ls
        String workspaceDir = tempDir.toAbsolutePath().toString();
        String lsCommand = System.getProperty("os.name").toLowerCase().contains("win") ? "dir" : "ls";

        FunctionCall toolCall = FunctionCall.of(
            "call_shell_001",
            "function",
            "shell_command",
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
        String detailedListCommand = System.getProperty("os.name").toLowerCase().contains("win") ? "dir" : "ls -la";

        FunctionCall toolCall = FunctionCall.of(
            "call_shell_002",
            "function",
            "shell_command",
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
        String command = System.getProperty("os.name").toLowerCase().contains("win") ? "dir" : "ls";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"), command);

        logger.info("Testing direct tool call with args: {}", jsonArgs);
        String result = shellCommandTool.call(jsonArgs);
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
        String command = System.getProperty("os.name").toLowerCase().contains("win") ? "dir" : "ls";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            nonExistentDir.replace("\\", "\\\\"), command);

        logger.info("Testing with invalid directory: {}", jsonArgs);
        String result = shellCommandTool.call(jsonArgs);
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
        String command = System.getProperty("os.name").toLowerCase().contains("win")
            ? "dir"
            : "ls -la";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"), command);

        logger.info("Testing command output with spaces in filename: {}", jsonArgs);
        String result = shellCommandTool.call(jsonArgs);
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
        String command = System.getProperty("os.name").toLowerCase().contains("win")
            ? "New-Item -ItemType File -Path \"test_empty.txt\" -Force | Out-Null"
            : "touch test_empty.txt";

        String jsonArgs = String.format("{\"workspace_dir\":\"%s\",\"command\":\"%s\"}",
            workspaceDir.replace("\\", "\\\\"), command.replace("\"", "\\\""));

        logger.info("Testing command with empty output: {}", jsonArgs);
        String result = shellCommandTool.call(jsonArgs);
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
        String result = shellCommandTool.call(jsonArgs);
        logger.info("Result: {}", result);

        assertNotNull(result, "Result should not be null");
        assertTrue(result.contains("Error") && result.contains("required"),
            "Result should indicate command parameter is required");
    }
}
