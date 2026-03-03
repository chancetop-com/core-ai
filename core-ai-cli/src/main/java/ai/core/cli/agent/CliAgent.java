package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.llm.LLMProviders;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.persistence.PersistenceProvider;
import ai.core.skill.SkillConfig;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.mcp.McpToolCalls;
import ai.core.tool.tools.AddMcpServerTool;
import ai.core.tool.tools.AskUserTool;
import ai.core.tool.tools.ManageSkillTool;

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
    public static Agent of(LLMProviders providers, String modelOverride, int maxTurn,
                           PersistenceProvider persistenceProvider, Path workspace,
                           Function<String, String> askUserHandler) {

        var workspaceInfo = buildWorkspaceInfo(workspace);
        var systemPrompt = """
                You are a helpful AI coding assistant.

                <workspace>
                %s
                </workspace>

                Always use the workspace directory as the working directory when executing shell commands or scripts.
                """.formatted(workspaceInfo);

        List<ToolCall> tools = new ArrayList<>(BuiltinTools.ALL);
        tools.add(AskUserTool.builder().questionHandler(askUserHandler).build());
        tools.add(AddMcpServerTool.builder().toolRegistrar(tools::addAll).build());
        tools.add(ManageSkillTool.builder().skillsDir(workspace.resolve(".core-ai/skills")).build());

        var builder = Agent.builder()
                .llmProvider(providers.getProvider())
                .systemPrompt(systemPrompt)
                .maxTurn(maxTurn)
                .toolCalls(tools)
                .temperature(0.8);

        // Skills: load from workspace and user home
        var skillConfig = SkillConfig.builder()
                .source("workspace", workspace.resolve(".core-ai/skills").toString(), 100)
                .source("user", Path.of(System.getProperty("user.home"), ".core-ai-cli", "skills").toString(), 50)
                .build();
        builder.skills(skillConfig);

        // MCP: connect to configured servers if available
        configureMcp(builder);

        if (persistenceProvider != null) {
            builder.persistenceProvider(persistenceProvider);
        }
        if (modelOverride != null) {
            builder.model(modelOverride);
        }

        return builder.build();
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
}
