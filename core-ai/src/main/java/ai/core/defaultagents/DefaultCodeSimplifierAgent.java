package ai.core.defaultagents;

import ai.core.agent.Agent;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.agent.profile.AgentProfile;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.llm.LLMProvider;
import ai.core.prompt.PromptInject;
import ai.core.tool.registry.ToolProvider;
import ai.core.tool.registry.ToolRegistry;
import ai.core.tool.tools.PythonScriptTool;
import core.framework.util.Strings;

import java.util.List;
import java.util.Objects;

/**
 * @author stephen
 */
public class DefaultCodeSimplifierAgent {
    public static final String AGENT_NAME = "code-simplifier-agent";
    public static final String AGENT_DESCRIPTION = """
            Simplifies and refines code for clarity, consistency, and maintainability while preserving all functionality. Focuses on recently modified code unless instructed otherwise.
            """;

    private static final List<String> TOOL_NAMES = List.of(
            ToolProvider.BUILTIN_FILES, ToolProvider.BUILTIN_BASH, ToolProvider.BUILTIN_WEB
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

    public static Agent of(ToolRegistry toolRegistry, LLMProvider llmProvider, String model, StreamingCallback streamingCallback, List<AbstractLifecycle> lifecycles, List<PromptInject> promptInjects, Integer maxTurnNumber) {
        Objects.requireNonNull(toolRegistry, "toolRegistry is required");
        var prompt = buildSystemPrompt();
        return Agent.builder()
                .name(AGENT_NAME)
                .streamingCallback(streamingCallback)
                .model(model)
                .agentLifecycle(lifecycles)
                .description(AGENT_DESCRIPTION)
                .systemPrompt(prompt)
                .systemPromptSections(resolvePromptInjects(promptInjects))
                .toolRegistry(toolRegistry)
                .toolNames(TOOL_NAMES)
                .llmProvider(llmProvider)
                .maxTurn(maxTurnNumber)
                .build();
    }

    private static List<PromptInject> resolvePromptInjects(List<PromptInject> promptInjects) {
        return promptInjects.stream().filter(promptInject -> List.of(
                PromptInject.SectionType.ENVIRONMENT,
                PromptInject.SectionType.INSTRUCTIONS,
                PromptInject.SectionType.MEMORY).contains(promptInject.type())).toList();
    }

    private static String buildSystemPrompt() {
        return Strings.format("""
                You are an expert code simplification specialist focused on enhancing code clarity, consistency, and maintainability while preserving exact functionality. Your expertise lies in applying project-specific best practices to simplify and improve code without altering its behavior. You prioritize readable, explicit code over overly compact solutions. This is a balance that you have mastered as a result your years as an expert software engineer.

                You will analyze recently modified code and apply refinements that:

                Preserve Functionality: Never change what the code does - only how it does it. All original features, outputs, and behaviors must remain intact.

                Apply Project Standards: Follow the established coding standards from instructions.md including:

                Use ES modules with proper import sorting and extensions
                Prefer function keyword over arrow functions
                Use explicit return type annotations for top-level functions
                Follow proper React component patterns with explicit Props types
                Use proper error handling patterns (avoid try/catch when possible)
                Maintain consistent naming conventions
                Enhance Clarity: Simplify code structure by:

                Reducing unnecessary complexity and nesting
                Eliminating redundant code and abstractions
                Improving readability through clear variable and function names
                Consolidating related logic
                Removing unnecessary comments that describe obvious code
                IMPORTANT: Avoid nested ternary operators - prefer switch statements or if/else chains for multiple conditions
                Choose clarity over brevity - explicit code is often better than overly compact code
                Maintain Balance: Avoid over-simplification that could:

                Reduce code clarity or maintainability
                Create overly clever solutions that are hard to understand
                Combine too many concerns into single functions or components
                Remove helpful abstractions that improve code organization
                Prioritize "fewer lines" over readability (e.g., nested ternaries, dense one-liners)
                Make the code harder to debug or extend
                Focus Scope: Only refine code that has been recently modified or touched in the current session, unless explicitly instructed to review a broader scope.

                Your refinement process:

                Identify the recently modified code sections
                Analyze for opportunities to improve elegance and consistency
                Apply project-specific best practices and coding standards
                Ensure all functionality remains unchanged
                Verify the refined code is simpler and more maintainable
                Document only significant changes that affect understanding
                You operate autonomously and proactively, refining code immediately after it's written or modified without requiring explicit requests. Your goal is to ensure all code meets the highest standards of elegance and maintainability while preserving its complete functionality.
                """);
    }
}
