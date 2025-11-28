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
import ai.core.tool.tools.WebFetchTool;
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

    @Test
    void test() {
        var agent = Agent.builder()
                .name("CodeGenTestAgent")
                .description("An agent to test code generation capabilities")
                .systemPrompt("""
                        You are Naixt Code agent that can write code based on user requirements.
                        
                        # Goal
                        You are expected to generate code for a "web automation test case described in natural language."
                        The user will send you their test case in natural-language form. Based on the user's description and the available environment information, you should produce the corresponding automated test code.
                        
                        While writing the code, you may need to interact with the Browser MCP tool to retrieve the HTML content of the relevant pages, analyze the DOM structure, and determine how the automation code should be written.
                        
                        You need to test the code after writing it and determine whether the automation script satisfies the user's test case.
                        Unless you are missing essential information, you should continue testing and improving your code.
                        Only when critical information is truly unavailable — for example, a missing login username — may you stop and ask the user for clarification.
                        
                        # Task Management
                        Use the write_todos tool to planning you coding task step by step.
                        {{system.agent.write.todos.system.prompt}}
                        
                        # About Web Automation
                        1. sometimes some button might occlusion by some toast or small window size, maximize the window, close the ad or accept the cookie to let the ui show complete.
                        2. sometimes page will be blank because of network issue or something else, refresh the page to reload entirely.
                        3. some cta take lot's of time: for example checkout, wait for it
                        
                        # Important
                        **Remember your task is to write code for further automation test, not only this time.**
                        
                        # Workflow
                        Use the chrome-devtools MCP tool to finish the testcase, it can help you get through the whole process.
                        Use playwright python to write the automation test code.
                        
                        # Current Environment
                        The current workspace is located at: {{workspace}}.
                        """)
                .toolCalls(List.of(
                        GrepFileTool.builder().build(),
                        GlobFileTool.builder().build(),
                        ReadFileTool.builder().build(),
                        WriteFileTool.builder().build(),
                        EditFileTool.builder().build(),
                        PythonScriptTool.builder().build(),
                        ShellCommandTool.builder().build(),
                        AsyncTaskOutputTool.builder().build(),
                        WebFetchTool.builder().build(),
                        WriteTodosTool.self()
                ))
                .mcpServers(List.of("chrome-devtools"), null)
                .llmProvider(llmProviders.getProvider())
                .maxTurn(30)
                .model("gpt-5-mini")
                .build();
        logger.info("setup agent: {}", agent);
        var testcase = """
                testcase: success order
                testcase steps:
                    1. navigate to https://mrkeke.connexup-uat.online/
                    2. login with phone number 8888888888 and verify number 123456 if not login
                    3. add coke to cart
                    4. checkout and place order with account's default credit card
                    5. get order number
                testcase expect: success get order number
                """;
        agent.run(Strings.format("""
                please write python code for this testcase:
                {}
                write code under the workspace, and run & test & debug to make sure it works.
                """, testcase), ExecutionContext.builder().customVariable("workspace", "D:\\automate-test-code-gen").build());
    }
}
