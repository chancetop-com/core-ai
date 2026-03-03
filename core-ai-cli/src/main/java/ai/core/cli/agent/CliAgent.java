package ai.core.cli.agent;

import ai.core.agent.Agent;
import ai.core.llm.LLMProviders;
import ai.core.persistence.PersistenceProvider;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;
import ai.core.tool.tools.AskUserTool;

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

        var builder = Agent.builder()
                .llmProvider(providers.getProvider())
                .systemPrompt(systemPrompt)
                .maxTurn(maxTurn)
                .toolCalls(tools)
                .temperature(0.8);

        if (persistenceProvider != null) {
            builder.persistenceProvider(persistenceProvider);
        }
        if (modelOverride != null) {
            builder.model(modelOverride);
        }

        return builder.build();
    }

    private static String buildWorkspaceInfo(Path workspace) {
        var sb = new StringBuilder();
        sb.append("Working directory: ").append(workspace.toAbsolutePath()).append("\n");

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
