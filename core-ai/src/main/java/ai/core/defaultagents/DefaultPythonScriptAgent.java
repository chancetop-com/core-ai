package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.formatter.formatters.DefaultCodeFormatter;
import ai.core.llm.LLMProvider;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.tools.PythonScriptTool;
import core.framework.util.Strings;

import java.util.List;

/**
 * @author stephen
 */
public class DefaultPythonScriptAgent {
    public static final String PYTHON_AGENT_NAME = "python-script-agent";
    public static final String PYTHON_AGENT_DESCRIPTION = "This agent is used to write and run python script.";

    public static String defaultPythonScriptAgentSystemPrompt(String additionSystemPrompt) {
        return Strings.format("""
                    You are an assistant to help users write and run python script.
                    Analyze user requirements and write python code to fulfill those requirements.
                    You need to take care of the python environment and system environment.
                    The Python code should preferably only use system libraries.
                    {}
                    """, additionSystemPrompt == null ? "" : additionSystemPrompt);
    }

    public static String defaultPythonScriptAgentPromptTemplate(String contextVariableTemplate) {
        return Strings.format("""
                    System: {{{system.environment}}}
                    Python: {{{system.python.environment}}}
                    {}
                    Request:
                    """, contextVariableTemplate == null ? "" : contextVariableTemplate);
    }

    public static PythonScriptTool pythonScriptTool() {
        return PythonScriptTool.builder()
                .name("python-script-exec")
                .description("This tool is used to run python script.")
                .parameters(List.of(
                        ToolCallParameter.builder()
                                .name("code")
                                .description("the code of the python script")
                                .classType(String.class)
                                .required(true).build())
                ).build();
    }

    public static Agent of(LLMProvider llmProvider, String additionSystemPrompt, String contextVariableTemplate) {
        return Agent.builder()
                .name(PYTHON_AGENT_NAME)
                .description(PYTHON_AGENT_DESCRIPTION)
                .systemPrompt(defaultPythonScriptAgentSystemPrompt(additionSystemPrompt))
                .promptTemplate(defaultPythonScriptAgentPromptTemplate(contextVariableTemplate))
                .toolCalls(List.of(pythonScriptTool()))
                .enableReflection(true)
                .formatter(new DefaultCodeFormatter())
                .llmProvider(llmProvider).build();
    }
}
