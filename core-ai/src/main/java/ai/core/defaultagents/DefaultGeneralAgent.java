package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.agent.profile.AgentProfile;
import ai.core.llm.LLMProvider;
import ai.core.prompt.PromptInject;
import ai.core.skill.SkillToolProvider;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolRegistry;
import java.util.List;
import java.util.Objects;

public class DefaultGeneralAgent {
    public static final String AGENT_NAME = "general-purpose-agent";
    public static final String AGENT_DESCRIPTION = """
            the general-purpose subagent that searches, analyzes, and edits code across a codebase while reporting findings concisely to the caller
            whenToUse:
                General-purpose agent for researching complex questions, searching for code, and executing multi-step tasks. When you are searching for a keyword or file and are not confident that you will find the right match in the first few tries use this agent to perform the search for you.
            """;

    private static final List<String> TOOL_NAMES = List.of(
            ToolProvider.BUILTIN_FILES, ToolProvider.BUILTIN_BASH, ToolProvider.BUILTIN_WEB, SkillToolProvider.SKILL
    );

    public static AgentProfile profile() {
        return new AgentProfile()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .systemPrompt(buildSystemPrompt())
                .tools(TOOL_NAMES)
                .source("builtin")
                .priority(0);
    }

    public static Agent of(ToolRegistry toolRegistry, LLMProvider llmProvider, String model, ExecutionContext context, Integer maxTurnNumber) {
        Objects.requireNonNull(toolRegistry, "toolRegistry is required");
        return Agent.builder()
                .name(AGENT_NAME)
                .streamingCallback(context.getStreamingCallback())
                .model(model)
                .agentLifecycle(context.getLifecycle())
                .description(AGENT_DESCRIPTION)
                .systemPrompt(buildSystemPrompt())
                .systemPromptSections(resolvePromptInjects(context.getPromptSections()))
                .toolRegistry(toolRegistry)
                .toolNames(TOOL_NAMES)
                .llmProvider(llmProvider)
                .maxTurn(maxTurnNumber)
                .build();
    }

    private static List<PromptInject> resolvePromptInjects(List<PromptInject> promptInjects) {
        if (promptInjects == null) return List.of();
        return promptInjects.stream().filter(promptInject -> List.of(
                PromptInject.SectionType.ENVIRONMENT,
                PromptInject.SectionType.INSTRUCTIONS,
                PromptInject.SectionType.MEMORY).contains(promptInject.type())).toList();
    }

    private static String buildSystemPrompt() {
        return """
                You are an agent for core-ai-cli. Given the user's message, you should use the tools available to complete the task. Complete the task fully—don't gold-plate, but don't leave it half-done.
                When you complete the task, respond with a concise report covering what was done and any key findings — the caller will relay this to the user, so it only needs the essentials.
                Your strengths:
                    - Searching for code, configurations, and patterns across large codebases
                    - Analyzing multiple files to understand system architecture
                    - Investigating complex questions that require exploring many files
                    - Performing multi-step research tasks
                Guidelines:
                    - For file searches: search broadly when you don't know where something lives. Use Read when you know the specific file path.
                    - For analysis: Start broad and narrow down. Use multiple search strategies if the first doesn't yield results.
                    - Be thorough: Check multiple locations, consider different naming conventions, look for related files.
                    - NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one.
                    - NEVER proactively create documentation files (*.md) or README files. Only create documentation files if explicitly requested.
                """;
    }
}
