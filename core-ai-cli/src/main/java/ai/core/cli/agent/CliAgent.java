package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.cli.plugin.PluginManager;
import ai.core.agent.ExecutionContext;
import ai.core.cli.hook.HookConfig;
import ai.core.cli.hook.ScriptHookLifecycle;
import ai.core.cli.hook.ScriptHookRunner;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.subagent.FileSubagentOutputSinkFactory;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.persistence.PersistenceProvider;
import ai.core.skill.SkillConfig;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.tool.tools.AddMcpServerTool;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.SkillTool;
import ai.core.tool.tools.TaskTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class CliAgent {

    public static Agent of(Config config) {
        var pluginManager = PluginManager.getInstance(Path.of(System.getProperty("user.home"), ".core-ai"));
        var skillConfig = buildSkillConfig(config, pluginManager);
        var tools = buildTools(config, skillConfig);

        var hookConfig = HookConfig.load(config.workspace, pluginManager);
        var hookLifecycle = hookConfig.isEmpty() ? null
                : new ScriptHookLifecycle(hookConfig, new ScriptHookRunner(config.workspace));

        var systemPrompt = buildSystemPrompt(config);
        if (hookLifecycle != null) {
            String sessionStartOutput = hookLifecycle.runSessionStartHooks();
            if (!sessionStartOutput.isEmpty()) {
                systemPrompt += "\n\n" + sessionStartOutput;
            }
        }
        var builder = Agent.builder()
                .llmProvider(config.providers.getDefaultProvider())
                .systemPrompt(systemPrompt)
                .maxTurn(config.maxTurn)
                .toolCalls(tools)
                .temperature(0.8);

        if (hookLifecycle != null) {
            builder.addAgentLifecycle(hookLifecycle);
        }

        configureMcp(builder);

        if (config.persistenceProvider != null) {
            builder.persistenceProvider(config.persistenceProvider);
        }
        if (config.modelOverride != null) {
            builder.model(config.modelOverride);
        }
        var agent = builder.build();
        agent.setExecutionContext(ExecutionContext.builder()
                .customVariables(Map.of("workspace", config.workspace.toAbsolutePath().toString()))
                .subagentOutputSinkFactory(new FileSubagentOutputSinkFactory(config.workspace.resolve(".core-ai/tasks")))
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

    private static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "skills");
    }

    private static String buildSystemPrompt(Config config) {
        var workspaceInfo = buildWorkspaceInfo(config.workspace);
        var sb = new StringBuilder("""
                You are a helpful AI coding assistant and  a personal assistant running inside core-ai.
                
                <workspace>
                %s
                </workspace>
                
                Always use the workspace directory as the working directory when executing shell commands or scripts.
                """.formatted(workspaceInfo));

        var instructions = loadProjectInstructions(config.workspace);
        sb.append("""

                ## Project Instructions

                File: .core-ai/instructions.md
                Use this file for project facts, conventions, build commands, and rules that apply to all interactions.

                <project-instructions>
                %s
                </project-instructions>
                """.formatted(instructions.isEmpty()
                ? "(empty - create .core-ai/instructions.md when project conventions or rules need to persist across sessions)"
                : instructions));

        if (config.memoryEnabled) {
            var mdMemoryProvider = new MdMemoryProvider(config.workspace);
            var mdMemoryContent = mdMemoryProvider.load();
            sb.append("""
                    
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
                    """.formatted(mdMemoryContent.isBlank() ? "(empty - initialize when user shares preferences or asks to remember)" : mdMemoryContent));
        }

        return sb.toString();
    }

    private static void configureMcp(ai.core.agent.AgentBuilder builder) {
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) return;
        var serverNames = manager.getServerNames();
        if (serverNames != null && !serverNames.isEmpty()) {
            var mcpTools = McpToolCalls.from(manager, new ArrayList<>(serverNames), null);
            builder.toolCalls(new ArrayList<>(mcpTools));
        }
    }

    private static String loadProjectInstructions(Path workspace) {
        var file = workspace.resolve(".core-ai/instructions.md");
        if (!Files.isRegularFile(file)) return "";
        try {
            return Files.readString(file).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static String buildWorkspaceInfo(Path workspace) {
        var sb = new StringBuilder(256);
        sb.append("Working directory: ").append(workspace.toAbsolutePath()).append('\n');

        try {
            var entries = Files.list(workspace)
                    .sorted()
                    .map(p -> {
                        var name = p.getFileName().toString();
                        return Files.isDirectory(p) ? name + "/" : name;
                    })
                    .collect(Collectors.joining("\n  "));
            if (!entries.isEmpty()) {
                sb.append("Contents:\n  ").append(entries);
            }
        } catch (IOException e) {
            sb.append("(Unable to list workspace contents)");
        }

        return sb.toString();
    }

    public record Config(LLMProviders providers, String modelOverride, int maxTurn,
                         PersistenceProvider persistenceProvider, Path workspace,
                         Function<String, String> askUserHandler,
                         boolean memoryEnabled) {

        public Config(LLMProviders providers, String modelOverride, int maxTurn,
                      PersistenceProvider persistenceProvider, Path workspace,
                      Function<String, String> askUserHandler) {
            this(providers, modelOverride, maxTurn, persistenceProvider, workspace, askUserHandler, true);
        }
    }
}
