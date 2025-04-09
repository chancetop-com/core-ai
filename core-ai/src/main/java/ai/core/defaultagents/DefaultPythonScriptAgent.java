package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.tools.PythonScriptTool;

import java.util.List;

/**
 * @author stephen
 */
public class DefaultPythonScriptAgent {
    public static Agent of(LLMProvider llmProvider) {
        return Agent.builder()
                .name("python-script-agent")
                .description("This agent is used to write and run python script.")
                .systemPrompt("""
                        You are an assistant to help users write and run python script.
                        Analyze user requirements and write python code to fulfill those requirements.
                        If the user provides a python script, you need to run it and return the result.
                        You need to take care of the python environment and system environment.
                        """)
                .promptTemplate("""
                        System: {{system_environment}}
                        Python: {{python_environment}}
                        Request:
                        """)
                .toolCalls(List.of(
                        PythonScriptTool.builder()
                                .name("python-script-exec")
                                .description("This tool is used to run python script.")
                                .parameters(List.of(
                                        ToolCallParameter.builder()
                                                .name("workspace_dir")
                                                .description("the workspace dir that the script to run")
                                                .type(String.class)
                                                .required(true).build(),
                                        ToolCallParameter.builder()
                                                .name("command")
                                                .description("the command to run the python script")
                                                .type(String.class)
                                                .required(true).build())
                                        ).build()
                ))
                .enableReflection(true)
                .llmProvider(llmProvider).build();
    }
}
