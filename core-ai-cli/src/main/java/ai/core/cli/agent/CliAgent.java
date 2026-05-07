package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.agent.AgentBuilder;
import ai.core.agent.ExecutionContext;
import ai.core.cli.hook.HookConfig;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.hook.ScriptHookRunner;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.plugin.PluginManager;
import ai.core.cli.remote.A2ARemoteAgentConfig;
import ai.core.cli.remote.A2ARemoteAgentDiscovery;
import ai.core.cli.remote.A2ARemoteServerConfig;
import ai.core.cli.remote.DelegateToRemoteAgentToolCall;
import ai.core.cli.remote.SearchRemoteAgentsToolCall;
import ai.core.cli.subagent.FileSubagentOutputSinkFactory;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.persistence.PersistenceProvider;
import ai.core.prompt.PromptInject;
import ai.core.skill.SkillConfig;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.tool.tools.AddMcpServerTool;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.SkillTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * @author stephen
 */
public class CliAgent {

    public static Agent of(Config config) {
        var pluginManager = PluginManager.getInstance(Path.of(System.getProperty("user.home"), ".core-ai"));
        var skillConfig = buildSkillConfig(config, pluginManager);
        var tools = buildTools(config, skillConfig);
        tools.addAll(0, mcpTools());
        tools.addAll(remoteAgentTools(config));

        var hookConfig = HookConfig.load(config.workspace, pluginManager);
        var hookLifecycle = hookConfig.isEmpty() ? null
                : new ScriptHookLifecycle(hookConfig, new ScriptHookRunner(config.workspace));

        String hookOutput = "";
        if (hookLifecycle != null) {
            hookOutput = hookLifecycle.runSessionStartHooks();
        }
        var builder = Agent.builder()
                .llmProvider(config.providers.getDefaultProvider())
                .maxTurn(config.maxTurn)
                .toolCalls(tools)
                .temperature(0.8);
        configureSystemPrompt(builder, config, hookOutput);

        if (hookLifecycle != null) {
            builder.addAgentLifecycle(hookLifecycle);
        }

        if (config.persistenceProvider != null) {
            builder.persistenceProvider(config.persistenceProvider);
        }
        if (config.modelOverride != null) {
            builder.model(config.modelOverride);
        }
        var agent = builder.build();
        agent.setExecutionContext(ExecutionContext.builder()
                .sessionId(config.sessionId)
                .customVariables(Map.of("workspace", config.workspace.toAbsolutePath().toString()))
                .persistenceProvider(config.persistenceProvider)
                .subagentOutputSinkFactory(new FileSubagentOutputSinkFactory(config.workspace.resolve(".core-ai/tasks")))
                .promptSections(constructPromptSection(config, hookOutput))
                .build());
        return agent;
    }

    private static SkillConfig buildSkillConfig(Config config, PluginManager pluginManager) {
        var builder = SkillConfig.builder()
                .source("workspace", config.workspace.resolve(".core-ai/skills").toString(), 100)
                .source("user", userSkillsDir().toString(), 50);

        int priority = 75; // Between workspace (100) and user (50)
        for (var source : pluginManager.getEnabledPluginSkillSources()) {
            builder.source("plugin:" + source[0], source[1], priority);
        }

        return builder.build();
    }

    private static List<ToolCall> buildTools(Config config, SkillConfig skillConfig) {
        List<ToolCall> tools = new ArrayList<>(BuiltinTools.ALL);
        tools.add(AskUserTool.builder().questionHandler(config.askUserHandler).build());
        tools.add(AddMcpServerTool.builder().toolRegistrar(tools::addAll).build());
        tools.add(SkillTool.builder()
                .sources(skillConfig.getSources())
                .maxFileSize(skillConfig.getMaxSkillFileSize())
                .workspaceDir(config.workspace.toAbsolutePath().toString())
                .build());
        return tools;
    }

    private static List<ToolCall> remoteAgentTools(Config config) {
        var catalog = new A2ARemoteAgentDiscovery().discoverCatalog(config.remoteAgents, config.remoteServers);
        if (catalog.isEmpty()) return List.of();
        return List.of(SearchRemoteAgentsToolCall.builder().catalog(catalog).build(),
                DelegateToRemoteAgentToolCall.builder().catalog(catalog).build());
    }

    private static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "skills");
    }

    private static void configureSystemPrompt(AgentBuilder builder, Config config, String hookOutput) {
        builder.systemPromptSections(constructPromptSection(config, hookOutput));
    }

    private static List<PromptInject> constructPromptSection(Config config, String hookOutput) {
        List<PromptInject> sections = new ArrayList<>();
        sections.add(config.coding ? new CodeBasePrompt() : new BasePrompt());
        sections.add(new EnvironmentPrompt(config.workspace));
        sections.add(new InstructionsPrompt(config.workspace));
        if (config.memoryEnabled) {
            sections.add(new MemoryPrompt(config.workspace));
        }
        sections.add(new HookPrompt(hookOutput));
        return sections;
    }

    private static List<ToolCall> mcpTools() {
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) return List.of();
        var serverNames = manager.getServerNames();
        if (serverNames != null && !serverNames.isEmpty()) {
            return new ArrayList<>(McpToolCalls.from(manager, new ArrayList<>(serverNames), null));
        }
        return List.of();
    }


    public record Config(LLMProviders providers, String modelOverride, int maxTurn,
                         PersistenceProvider persistenceProvider, Path workspace,
                         Function<String, String> askUserHandler,
                         boolean memoryEnabled,
                         boolean coding,
                         String sessionId,
                         List<A2ARemoteAgentConfig> remoteAgents,
                         List<A2ARemoteServerConfig> remoteServers) {
    }

    // ---- PromptInject implementations ----

    record BasePrompt() implements PromptInject {
        @Override
        public String inject() {
            return "You are a helpful AI coding assistant and a personal assistant running inside core-ai.";
        }

        @Override
        public SectionType type() {
            return SectionType.IDENTITY;
        }
    }

    record CodeBasePrompt() implements PromptInject {
        @Override
        @SuppressWarnings("MethodLength")
        public String inject() {
            return """
                    You are core-ai-cli, the best coding agent on the planet.
                    
                    You are an interactive CLI tool that helps users with software engineering tasks. Use the instructions below and the tools available to you to assist the user.
                    
                    IMPORTANT: You must NEVER generate or guess URLs for the user unless you are confident that the URLs are for helping the user with programming. You may use URLs provided by the user in their messages or local files.
                    
                    If the user asks for help or wants to give feedback inform them of the following:
                    - ctrl+p to list available actions
                    - To give feedback, users should report the issue at
                      https://github.com/chancetop-com/core-ai
                    
                    When the user directly asks about core-ai-cli (eg. "can core-ai-cli do...", "does core-ai-cli have..."), or asks in second person (eg. "are you able...", "can you do..."), or asks how to use a specific core-ai-cli feature (eg. implement a hook, write a slash command, or install an MCP server), use the WebFetch tool to gather information to answer the question from core-ai-cli docs. The list of available docs is available at https://github.com/chancetop-com/core-ai/tree/master/docs
                    
                    # Tone and style
                    - Only use emojis if the user explicitly requests it. Avoid using emojis in all communication unless asked.
                    - Your output will be displayed on a command line interface. Your responses should be short and concise. You can use GitHub-flavored markdown for formatting, and will be rendered in a monospace font using the CommonMark specification.
                    - Output text to communicate with the user; all text you output outside of tool use is displayed to the user. Only use tools to complete tasks. Never use tools like Bash or code comments as means to communicate with the user during the session.
                    - NEVER create files unless they're absolutely necessary for achieving your goal. ALWAYS prefer editing an existing file to creating a new one. This includes markdown files.
                    
                    # Professional objectivity
                    Prioritize technical accuracy and truthfulness over validating the user's beliefs. Focus on facts and problem-solving, providing direct, objective technical info without any unnecessary superlatives, praise, or emotional validation. It is best for the user if core-ai-cli honestly applies the same rigorous standards to all ideas and disagrees when necessary, even if it may not be what the user wants to hear. Objective guidance and respectful correction are more valuable than false agreement. Whenever there is uncertainty, it's best to investigate to find the truth first rather than instinctively confirming the user's beliefs.
                    
                    # Task Management
                    You have access to the write_todos tools to help you manage and plan tasks. Use these tools VERY frequently to ensure that you are tracking your tasks and giving the user visibility into your progress.
                    These tools are also EXTREMELY helpful for planning tasks, and for breaking down larger complex tasks into smaller steps. If you do not use this tool when planning, you may forget to do important tasks - and that is unacceptable.
                    
                    It is critical that you mark todos as completed as soon as you are done with a task. Do not batch up multiple tasks before marking them as completed.
                    
                    Examples:
                    
                    <example>
                    user: Run the build and fix any type errors
                    assistant: I'm going to use the write_todos tool to write the following items to the todo list:
                    - Run the build
                    - Fix any type errors
                    
                    I'm now going to run the build using Bash.
                    
                    Looks like I found 10 type errors. I'm going to use the write_todos tool to write 10 items to the todo list.
                    
                    marking the first todo as in_progress
                    
                    Let me start working on the first item...
                    
                    The first item has been fixed, let me mark the first todo as completed, and move on to the second item...
                    ..
                    ..
                    </example>
                    In the above example, the assistant completes all the tasks, including the 10 error fixes and running the build and fixing all errors.
                    
                    <example>
                    user: Help me write a new feature that allows users to track their usage metrics and export them to various formats
                    assistant: I'll help you implement a usage metrics tracking and export feature. Let me first use the write_todos tool to plan this task.
                    Adding the following todos to the todo list:
                    1. Research existing metrics tracking in the codebase
                    2. Design the metrics collection system
                    3. Implement core metrics tracking functionality
                    4. Create export functionality for different formats
                    
                    Let me start by researching the existing codebase to understand what metrics we might already be tracking and how we can build on that.
                    
                    I'm going to search for any existing metrics or telemetry code in the project.
                    
                    I've found some existing telemetry code. Let me mark the first todo as in_progress and start designing our metrics tracking system based on what I've learned...
                    
                    [Assistant continues implementing the feature step by step, marking todos as in_progress and completed as they go]
                    </example>
                    
                    
                    # Doing tasks
                    The user will primarily request you perform software engineering tasks. This includes solving bugs, adding new functionality, refactoring code, explaining code, and more. For these tasks the following steps are recommended:
                    -\s
                    - Use the write_todos tool to plan the task if required
                    
                    - Tool results and user messages may include <system-reminder> tags. <system-reminder> tags contain useful information and reminders. They are automatically added by the system, and bear no direct relation to the specific tool results or user messages in which they appear.
                    
                    
                    # Tool usage policy
                    - When doing file search, prefer to use the Task tool in order to reduce context usage.
                    - You should proactively use the Task tool with specialized agents when the task at hand matches the agent's description.
                    
                    - When WebFetch returns a message about a redirect to a different host, you should immediately make a new WebFetch request with the redirect URL provided in the response.
                    - You can call multiple tools in a single response. If you intend to call multiple tools and there are no dependencies between them, make all independent tool calls in parallel. Maximize use of parallel tool calls where possible to increase efficiency. However, if some tool calls depend on previous calls to inform dependent values, do NOT call these tools in parallel and instead call them sequentially. For instance, if one operation must complete before another starts, run these operations sequentially instead. Never use placeholders or guess missing parameters in tool calls.
                    - If the user specifies that they want you to run tools "in parallel", you MUST send a single message with multiple tool use content blocks. For example, if you need to launch multiple agents in parallel, send a single message with multiple Task tool calls.
                    - Use specialized tools instead of bash commands when possible, as this provides a better user experience. For file operations, use dedicated tools: Read for reading files instead of cat/head/tail, Edit for editing instead of sed/awk, and Write for creating files instead of cat with heredoc or echo redirection. Reserve bash tools exclusively for actual system commands and terminal operations that require shell execution. NEVER use bash echo or other command-line tools to communicate thoughts, explanations, or instructions to the user. Output all communication directly in your response text instead.
                    - VERY IMPORTANT: When exploring the codebase to gather context or to answer a question that is not a needle query for a specific file/class/function, it is CRITICAL that you use the Task tool instead of running search commands directly.
                    <example>
                    user: Where are errors from the client handled?
                    assistant: [Uses the Task tool to find the files that handle client errors instead of using Glob or Grep directly]
                    </example>
                    <example>
                    user: What is the codebase structure?
                    assistant: [Uses the Task tool]
                    </example>
                    
                    IMPORTANT: Always use the write_todos tool to plan and track tasks throughout the conversation.
                    
                    # Code References
                    
                    When referencing specific functions or pieces of code include the pattern `file_path:line_number` to allow the user to easily navigate to the source code location.
                    
                    <example>
                    user: Where are errors from the client handled?
                    assistant: Clients are marked as failed in the `connectToServer` function in src/services/process.ts:712.
                    </example>
                    
                    """;
        }

        @Override
        public SectionType type() {
            return SectionType.IDENTITY;
        }
    }

    record EnvironmentPrompt(Path workspace) implements PromptInject {
        @Override
        public SectionType type() {
            return SectionType.ENVIRONMENT;
        }

        @Override
        public String inject() {
            var gitRepo = Files.isDirectory(workspace.resolve(".git")) ? "yes" : "no";
            var platform = platformName();
            var date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy"));
            return """
                    <env>
                        Working directory: %s
                        Workspace root folder: %s
                        Is directory a git repo: %s
                        Platform: %s
                        Today's date: %s
                    </env>
                    """.formatted(workspace.toAbsolutePath(), workspace.toAbsolutePath(), gitRepo, platform, date);
        }

        private static String platformName() {
            var os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            if (os.contains("mac") || os.contains("darwin")) return "darwin";
            if (os.contains("win")) return "win32";
            return "linux";
        }
    }

    record InstructionsPrompt(Path workspace) implements PromptInject {
        private static final String[] PROJECT_FILES = {"instructions.md", "AGENTS.md", "CLAUDE.md"};

        @Override
        public SectionType type() {
            return SectionType.INSTRUCTIONS;
        }

        @Override
        public String inject() {
            var sb = new StringBuilder();
            var projectPaths = findProjectInstructions(workspace);
            for (var path : projectPaths) {
                try {
                    var content = Files.readString(path).trim();
                    if (!content.isEmpty()) {
                        sb.append("Instructions from: ").append(path).append('\n').append(content);
                    }
                } catch (IOException ignored) {
                    // Ignore unreadable optional project instruction files.
                }
            }
            return sb.toString();
        }

        private static List<Path> findProjectInstructions(Path workspace) {
            var found = new ArrayList<Path>();
            var coreAiDir = workspace.resolve(".core-ai");
            for (var fileName : PROJECT_FILES) {
                var file = coreAiDir.resolve(fileName);
                if (Files.isRegularFile(file)) {
                    found.add(file);
                }
            }
            for (var fileName : PROJECT_FILES) {
                var file = workspace.resolve(fileName);
                if (Files.isRegularFile(file) && found.stream().noneMatch(p -> p.getFileName().toString().equals(fileName))) {
                    found.add(file);
                }
            }
            return found;
        }
    }

    record MemoryPrompt(Path workspace) implements PromptInject {
        @Override
        public SectionType type() {
            return SectionType.MEMORY;
        }

        @Override
        public String inject() {
            var mdContent = new MdMemoryProvider(workspace).load();
            return """
                    ## Memory
                    
                    Persistent structured memory at .core-ai/memory/.
                    Index: .core-ai/MEMORY.md | Topic files: .core-ai/memory/*.md
                    Each topic file has YAML frontmatter (name, description, type: user/feedback/project/reference).
                    
                    Index structure: | File | Description | Created | Updated |
                    Description column: use the `description` field from the file's YAML frontmatter.
                    
                    Reading: use md_memory_tool to search/get, or read_file for full content.
                    Writing: use write_file/edit_file to create/update topic files. \
                    Update MEMORY.md index when adding or removing files. \
                    Check existing memories first to avoid duplicates; merge into existing files when possible.
                    
                    <memories>
                    %s
                    </memories>
                    """.formatted(mdContent.isBlank() ? "(empty - initialize when user shares preferences or asks to remember)" : mdContent);
        }
    }

    record HookPrompt(String output) implements PromptInject {
        @Override
        public SectionType type() {
            return SectionType.HOOK;
        }

        @Override
        public String inject() {
            return output;
        }
    }
}
