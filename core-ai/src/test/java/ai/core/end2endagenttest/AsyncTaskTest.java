package ai.core.end2endagenttest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.AsyncTaskOutputTool;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ShellCommandTool;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
@Disabled
class AsyncTaskTest extends IntegrationTest {
    @Inject
    LLMProviders llmProviders;

    @Test
    void testAsyncPythonScript() {
        var agent = Agent.builder()
                .name("async-task-test-agent")
                .description("An agent to test async task execution")
                .systemPrompt("""
                        You are an assistant that can execute Python scripts.

                        When asked to run a long-running task:
                        1. Execute the task with async=true parameter
                        2. You'll get a task_id in the response
                        3. Use async_task_output tool with action='poll' and the task_id to check status
                        4. If status is PENDING, poll again after a moment
                        5. If status is COMPLETED, report the result
                        6. If status is FAILED, report the error
                        """)
                .toolCalls(List.of(
                        PythonScriptTool.builder().build(),
                        AsyncTaskOutputTool.builder().build()
                ))
                .llmProvider(llmProviders.getProvider())
                .model("gpt-5-mini")
                .maxTurn(10)
                .build();

        var result = agent.run("""
                Run a Python script asynchronously that:
                1. Sleeps for 2 seconds
                2. Then prints result of: sum(range(1, 1001))
                output:
                the result
                """, ExecutionContext.empty());

        assertTrue(result.contains("500500"), "Expected result to contain the sum output");
    }

    @Test
    void testAsyncShellCommand() {
        var agent = Agent.builder()
                .name("async-shell-test-agent")
                .description("An agent to test async shell command execution")
                .systemPrompt("""
                        You are an assistant that can execute shell commands.

                        When asked to run a command asynchronously:
                        1. Execute the command with async=true
                        2. Use async_task_output tool with action='poll' to check task status
                        3. Keep polling until the task completes
                        4. Report the final result
                        """)
                .toolCalls(List.of(
                        ShellCommandTool.builder().build(),
                        AsyncTaskOutputTool.builder().build()
                ))
                .llmProvider(llmProviders.getProvider())
                .model("gpt-5-mini")
                .maxTurn(10)
                .build();

        var result = agent.run("""
                Run a shell command asynchronously that echoes "Hello from async shell task".
                Use async=true, then poll for the result and tell me what it printed.
                """, ExecutionContext.empty());

        assertTrue(result.toLowerCase(Locale.getDefault()).contains("hello") || result.toLowerCase(Locale.getDefault()).contains("async"),
                "Expected result to contain the echo output");
    }
}
