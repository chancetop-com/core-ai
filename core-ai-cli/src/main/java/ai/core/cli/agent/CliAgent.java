package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.memory.MemoryProvider;
import ai.core.persistence.PersistenceProvider;
import ai.core.skill.SkillConfig;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.tool.tools.AddMcpServerTool;
import ai.core.tool.tools.GrepFileTool;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.ManageSkillTool;
import ai.core.tool.tools.MemoryTool;
import ai.core.tool.tools.ReadSkillReferenceTool;
import ai.core.tool.tools.SkillTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class CliAgent {

    public static Agent of(Config config) {
        var skillConfig = buildSkillConfig(config);
        var tools = buildTools(config, skillConfig);
        var builder = Agent.builder()
                .llmProvider(config.providers.getProvider())
                .systemPrompt(buildSystemPrompt(config))
                .maxTurn(config.maxTurn)
                .toolCalls(tools)
                .temperature(0.8);

        builder.skills(skillConfig);
        configureMcp(builder);

        if (config.persistenceProvider != null) {
            builder.persistenceProvider(config.persistenceProvider);
        }
        if (config.modelOverride != null) {
            builder.model(config.modelOverride);
        }
        return builder.build();
    }

    private static SkillConfig buildSkillConfig(Config config) {
        return SkillConfig.builder()
                .source("workspace", config.workspace.resolve(".core-ai/skills").toString(), 100)
                .source("user", userSkillsDir().toString(), 50)
                .build();
    }

    private static List<ToolCall> buildTools(Config config, SkillConfig skillConfig) {
        List<ToolCall> tools = new ArrayList<>(BuiltinTools.ALL);
        tools.replaceAll(tool -> {
            if (tool instanceof GrepFileTool) {
                return GrepFileTool.builder().workingDir(config.workspace.toFile()).build();
            }
            return tool;
        });
        tools.add(AskUserTool.builder().questionHandler(config.askUserHandler).build());
        tools.add(AddMcpServerTool.builder().toolRegistrar(tools::addAll).build());
        tools.add(ManageSkillTool.builder()
                .skillsDir(config.workspace.resolve(".core-ai/skills"))
                .userSkillsDir(userSkillsDir())
                .build());
        tools.add(SkillTool.builder()
                .sources(skillConfig.getSources())
                .maxFileSize(skillConfig.getMaxSkillFileSize())
                .build());
        tools.add(ReadSkillReferenceTool.builder()
                .sources(skillConfig.getSources())
                .maxFileSize(skillConfig.getMaxSkillFileSize())
                .build());
        if (config.memory != null) {
            tools.add(MemoryTool.builder().provider(config.memory).build());
        }
        return tools;
    }

    private static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "skills");
    }

    private static String buildSystemPrompt(Config config) {
        var workspaceInfo = buildWorkspaceInfo(config.workspace);
        var sb = new StringBuilder("""
                You are a helpful AI coding assistant.

                ## Skills (mandatory)
                Before replying: scan <available_skills> <description> entries.
                  - If exactly one skill clearly applies: read its SKILL.md at <location> with `read`, then follow it.
                  - If multiple could apply: choose the most specific one, then read/follow it.
                  - If none clearly apply: do not read any SKILL.md.
                  - If a skill defines an execution flow (e.g. actions before and after the main task), you MUST follow it.
                  - After completing the main task, re-evaluate whether any skill should be triggered based on what happened.
                Constraints: never read more than one skill up front; only read after selecting.
                When a skill has a <base_dir>, use it as the default path for grep_file and read operations within that skill.

                {{AVAILABLE_SKILLS}}

                <workspace>
                %s
                </workspace>

                Always use the workspace directory as the working directory when executing shell commands or scripts.
                """.formatted(workspaceInfo));

        var instructions = loadProjectInstructions(config.workspace);
        if (!instructions.isEmpty()) {
            sb.append("\n<project-instructions>\n").append(instructions).append("</project-instructions>\n");
        }

        if (config.memory != null) {
            sb.append("""

                You have access to persistent memory (MEMORY.md) that survives across sessions.
                - Existing memories are shown in <memory> tags below
                - Use memory_tool with action='read' then 'edit' to organize memories into proper sections
                - Do NOT save session-specific context or duplicate existing memories
                - Reference existing memories naturally without announcing them
                - If a skill in <available_skills> describes memory-related triggers (e.g. detecting user \
                preferences, corrections, or conventions), follow its instructions to proactively persist \
                relevant information using memory_tool throughout the conversation
                """);
            var memoryContent = config.memory.load();
            if (!memoryContent.isBlank()) {
                sb.append("\n<memory>\n").append(memoryContent).append("</memory>\n");
            }
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
                         Function<String, String> askUserHandler, MemoryProvider memory) {
    }
}
