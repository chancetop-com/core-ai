package ai.core.codegentest;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProviders;
import ai.core.tool.tools.AsyncTaskOutputTool;
import ai.core.tool.tools.EditFileTool;
import ai.core.tool.tools.GlobFileTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ReadFileTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.tool.tools.WriteFileTool;
import ai.core.tool.tools.WriteTodosTool;
import core.framework.inject.Inject;
import core.framework.util.Strings;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
//@Disabled
class CodeGenTest extends IntegrationTest {

    public static final String SNAPSHOT_TOOLS_DESC = """
            - `browser_snapshot`: Capture an accessibility snapshot (semantic tree) of the current page.
            - `browser_click`: Click on an element identified by its description and `ref` from the snapshot.
            - `browser_type`: Type text into an element identified by its `ref`, optionally submitting.
            - `browser_navigate`: Navigate to a specific URL.
            - `browser_navigate_back`: Go back in history.
            - `browser_navigate_forward`: Go forward in history.
            - `browser_select_option`: Select option(s) in a dropdown identified by its `ref`.
            - `browser_take_screenshot`: Take a screenshot. **Use arguments to request a raw PNG image if possible (e.g., by setting type to 'png').** This is primarily for user reference/recording, not actions.
            - (Potentially others like tab management, file handling, etc.)
            """;

    public static final String SNAPSHOT_INTERACTION_INST = """
            When interacting with elements (`browser_click`, `browser_type`, `browser_select_option`), ALWAYS use the `ref` provided in the accessibility snapshot (`browser_snapshot`) for the target element.
            Describe the element clearly in the `element` parameter for user understanding and logging.
            """;

    private final Logger logger = LoggerFactory.getLogger(CodeGenTest.class);
    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var agent = Agent.builder()
                .name("CodeGenTestAgent")
                .description("An agent to test code generation capabilities")
                .systemPrompt(Strings.format("""
                        You are a web automation code generation agent named Naixt Code. Your task is to convert natural-language test cases into working Playwright Python automation scripts.

                        # Workflow

                        ## Phase 1: MCP Execution (MANDATORY)
                        1. **Plan first**: Use `write_todos` to break down the test case steps into a checklist
                        2. **Execute each step**: Use Playwright MCP tools to complete each todo item
                           - Navigate through every step of the test case
                           - Handle all interactions (clicks, inputs, selections, etc.)
                           - Mark each todo as completed when done
                        3. **Verify success**: You MUST achieve the test case's expected result using MCP tools
                        4. **Record details**: Note the exact selectors, element refs, and flow you used

                        ⚠️ **GATE: DO NOT proceed to Phase 2 until you have successfully completed ALL todos in Phase 1 and verified the expected result.**

                        ## Phase 2: Code Generation And Debugging
                        Only after MCP execution succeeds:
                        1. Use `write_todos` to create a NEW todo list that MUST include these steps:
                           - Write Playwright Python script
                           - Execute script
                           - Debug and fix any errors (repeat until success)
                           - Verify test case expected result is achieved
                        2. Write Playwright Python code that replicates the exact steps you performed
                        3. Execute the script
                        4. If execution fails or expected result is not achieved:
                           - Analyze the error message
                           - Fix the script
                           - Re-run until it passes
                        5. Only mark Phase 2 as complete when the script runs successfully and meets the expected result

                        # Task Management

                        Use the `write_todos` tool to plan your tasks step by step.
                        {{system.agent.write.todos.system.prompt}}

                        # Playwright MCP Tools

                        Use these tools for interactive execution:
                        {}

                        {}

                        # Common Web Automation Issues

                        - **Obscured elements**: Buttons may be hidden by toasts, modals, or cookie banners. Maximize the window, dismiss dialogs, or scroll to reveal elements.
                        - **Blank pages**: Network issues can cause incomplete loads. Refresh or wait for network idle.
                        - **Slow operations**: Actions like checkout may take time. Use explicit waits for expected elements or states.
                        - **Cookie Banner Handling**: Many sites display cookie consent banners that can block interactions. Look for and click "Accept" or "Reject" buttons to dismiss them.

                        # Output Requirements

                        Your final deliverable is a **reusable Playwright Python script** saved to the workspace. The script should:
                        - Be self-contained and runnable independently
                        - Include appropriate waits and error handling

                        # Debugging

                        MCP interactive sessions and standalone scripts may behave differently due to session state (login status), timing, etc.
                        Ensure the final script handles these differences and runs correctly on its own.
                        Add detailed logging in script to trace execution flow and aid debugging.

                        **Debugging workflow:**
                        1. Run the script with `shell_command`: `python <script_path>`
                        2. If it fails, read the error output carefully
                        3. Use `edit_file` to fix the issue
                        4. Re-run until the script completes successfully
                        5. Verify the output contains the expected result

                        # Completion Criteria

                        You are ONLY done when:
                        - The Python script file exists in the workspace
                        - The script has been executed (not just syntax checked)
                        - The script ran successfully without errors
                        - The expected result was achieved (e.g., order number was printed)

                        DO NOT end the task if any of these criteria are not met!

                        # Environment

                        Workspace: {{workspace}}
                        """, SNAPSHOT_TOOLS_DESC, SNAPSHOT_INTERACTION_INST))
                .toolCalls(List.of(
                        GrepFileTool.builder().build(),
                        GlobFileTool.builder().build(),
                        ReadFileTool.builder().build(),
                        WriteFileTool.builder().build(),
                        EditFileTool.builder().build(),
                        PythonScriptTool.builder().build(),
                        ShellCommandTool.builder().build(),
                        AsyncTaskOutputTool.builder().build(),
//                        WebFetchTool.builder().build(),
                        WriteTodosTool.self()
                ))
                .mcpServers(List.of("playwright"), null)
                .llmProvider(llmProviders.getProvider())
                .maxTurn(50)
                .model("gpt-5-mini")
                .build();
        logger.info("setup agent: {}", agent);
        // todo: thinking how to rewrite testcase query for better agent understanding and performance
        // 1. use rag when we have more testcases to help rewrite agent for better understanding of the domain
        // 2.
        var testcase = """
                testcase: success order
                testcase steps:
                    1. navigate to https://mrkeke.connexup-uat.online/
                    2. login with phone number 8888888888 and verify number 123456 if not login
                    3. open PICKUP stores selection and select Flushing for order
                    4. add coke to cart
                    5. checkout and place order with account's default credit card
                    6. get order number
                testcase expect: success get order number
                """;
        agent.run(Strings.format("""
                please write python code for this testcase:
                {}
                write code under the workspace, and run & test & debug to make sure it works and satisfy the testcase expect.
                """, testcase), ExecutionContext.builder().customVariable("workspace", "D:\\automate-test-code-gen").build());
        logger.info(Strings.format("agent token cost: total={}, input={}, output={}",
                agent.getCurrentTokenUsage().getTotalTokens(),
                agent.getCurrentTokenUsage().getPromptTokens(),
                agent.getCurrentTokenUsage().getCompletionTokens()));
    }
}
