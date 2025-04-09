package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.tools.ShellCommandTool;
import core.framework.util.Strings;

import java.util.List;

/**
 * @author stephen
 */
public class DefaultShellCommandAgent {
    public static Agent of(LLMProvider llmProvider, List<String> tools) {
        return Agent.builder()
                .name("shell-command-agent")
                .description(Strings.format("Write and execute shell command based on these tools: {}.", String.join(", ", tools)))
                .systemPrompt(Strings.format("""
                        You are an assistant to help users write and execute shell command based on the available tools and system environment to fulfill user requirements.
                        Analyze user requirements and write instructions tailored to the user's operating system environment based on available tools to fulfill those requirements.
                        Tools available to you: {}.
                        Do not use any other tools except the available tools.
                        """, String.join(", ", tools)))
                .promptTemplate("""
                        System: {{{system_environment}}}
                        Shell: java processBuilder
                        Request:
                        """)
                .toolCalls(List.of(
                        ShellCommandTool.builder()
                                .name("shell-command-exec")
                                .description("This tool is used to run shell command.")
                                .parameters(List.of(
                                        ToolCallParameter.builder()
                                                .name("workspace_dir")
                                                .description("the workspace dir that to run the command")
                                                .type(String.class)
                                                .required(true).build(),
                                        ToolCallParameter.builder()
                                                .name("command")
                                                .description("the command string")
                                                .type(String.class)
                                                .required(true).build())
                                ).build()
                ))
                .enableReflection(true)
                .llmProvider(llmProvider).build();
    }
}
