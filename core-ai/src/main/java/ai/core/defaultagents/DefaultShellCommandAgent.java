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
    public static final String SHELL_COMMAND_AGENT_NAME = "shell-command-agent";
    public static final String SHELL_COMMAND_AGENT_DESCRIPTION = "shell-command-agent is an agent that helps users write and execute shell commands base on these tools: {{{system.available.commands}}}.";

    public static String defaultShellAgentSystemPrompt(String additionSystemPrompt) {
        return Strings.format("""
                    You are an assistant to help users write and execute shell command based on the available tools and system environment to fulfill user requirements.
                    Analyze user requirements and write instructions tailored to the user's operating system environment based on available tools to fulfill those requirements.
                    Tools available to you: {{{system.available.commands}}}.
                    Do not use any other tools except the available tools.
                    You need to carefully analyze the result of the command execution and determine whether it is a syntax error or an actual result failure, and decide whether it is necessary to attempt an alternative solution.
                    When dealing with path-related information, try to output the absolute path as much as possible.If encountering a relative path, the result should carefully (don't miss directory for example two level directory have the same name) combine the workspace path and the output path to provide an absolute path.
                    {}
                    """, additionSystemPrompt == null ? "" : additionSystemPrompt);
    }

    public static String defaultShellAgentPromptTemplate(String contextVariableTemplate) {
        return Strings.format("""
                    System: {{{system.environment}}}
                    Shell: java processBuilder
                    {}
                    Request:
                    """, contextVariableTemplate == null ? "" : contextVariableTemplate);
    }

    public static ShellCommandTool shellCommandToolCall() {
        return ShellCommandTool.builder()
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
                ).build();
    }

    public static Agent of(LLMProvider llmProvider, List<String> tools) {
        return of(llmProvider, tools, "", "");
    }
    public static Agent of(LLMProvider llmProvider, List<String> tools, String additionSystemPrompt, String contextVariableTemplate) {
        var agent = Agent.builder()
                .name(SHELL_COMMAND_AGENT_NAME)
                .description(SHELL_COMMAND_AGENT_DESCRIPTION)
                .systemPrompt(defaultShellAgentSystemPrompt(additionSystemPrompt))
                .promptTemplate(defaultShellAgentPromptTemplate(contextVariableTemplate))
                .toolCalls(List.of(shellCommandToolCall()))
                .enableReflection(true)
                .llmProvider(llmProvider).build();
        agent.putSystemVariable("system.available.commands", String.join(",", tools));
        return agent;
    }
}
