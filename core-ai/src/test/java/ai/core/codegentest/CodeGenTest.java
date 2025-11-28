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
@Disabled
class CodeGenTest extends IntegrationTest {
    private final Logger logger = LoggerFactory.getLogger(CodeGenTest.class);
    @Inject
    LLMProviders llmProviders;

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

    @Test
    void test() {
        var agent = Agent.builder()
                .name("CodeGenTestAgent")
                .description("An agent to test code generation capabilities")
                .systemPrompt(Strings.format("""
                        You are a web automation code generation agent. Your task is to convert natural-language test cases into working Playwright Python automation scripts.

                        # Workflow

                        1. **Explore**: Use the Playwright MCP tools to navigate the target website interactively. Understand the page structure, element selectors, and user flow.
                        2. **Plan**: Use `write_todos` to break down the test case into discrete steps.
                        3. **Write**: Generate Playwright Python code that replicates the explored flow.
                        4. **Test & Debug**: Run the script, identify failures, and iterate until it passes.

                        Continue testing and improving until the script satisfies the test case expectations. Only stop to ask the user when critical information is missing (e.g., login credentials).

                        # Task Management

                        Use the `write_todos` tool to plan your coding tasks step by step.
                        {{system.agent.write.todos.system.prompt}}

                        # Playwright MCP Tools

                        Use these tools for interactive exploration and validation:
                        {}

                        {}

                        # Common Web Automation Issues

                        - **Obscured elements**: Buttons may be hidden by toasts, modals, or cookie banners. Maximize the window, dismiss dialogs, or scroll to reveal elements.
                        - **Blank pages**: Network issues can cause incomplete loads. Refresh or wait for network idle.
                        - **Slow operations**: Actions like checkout may take time. Use explicit waits for expected elements or states.

                        # Output Requirements

                        Your final deliverable is a **reusable Playwright Python script** saved to the workspace. The script should:
                        - Be self-contained and runnable independently
                        - Include appropriate waits and error handling
                        - Follow the test case steps accurately

                        # Debugging
                        
                        Take care of the different between interactive MCP operations and script execution, they might behave differently due to session states(login or not), timing, etc.
                        Use MCP tools for exploration, but ensure the final script runs correctly on its own.

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
                .maxTurn(30)
                .model("gpt-5-mini")
                .build();
        logger.info("setup agent: {}", agent);
        var testcase = """
                testcase: success order
                testcase steps:
                    1. navigate to https://mrkeke.connexup-uat.online/, select PICKUP store Flushing use this location for order
                    2. login with phone number 8888888888 and verify number 123456 if not login
                    3. add coke to cart
                    4. checkout and place order with account's default credit card
                    5. get order number
                testcase expect: success get order number
                """;
        agent.run(Strings.format("""
                please write python code for this testcase:
                {}
                write code under the workspace, and run & test & debug to make sure it works and satisfy the testcase expect.
                """, testcase), ExecutionContext.builder().customVariable("workspace", "D:\\automate-test-code-gen").build());
    }
}
