package ai.core.cli.agent;

import ai.core.prompt.PromptInject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * @author stephen
 */
record CliAgentInstructionsPrompt(Path workspace) implements PromptInject {
    private static final String[] PROJECT_FILES = {"instructions.md", "AGENTS.md", "CLAUDE.md"};

    @Override
    public SectionType type() {
        return SectionType.INSTRUCTIONS;
    }

    @Override
    public String inject() {
        var sb = new StringBuilder(256);
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
        var coreAiDir = workspace.resolve(".core-ai");
        for (var fileName : PROJECT_FILES) {
            var file = coreAiDir.resolve(fileName);
            if (Files.isRegularFile(file)) {
                return List.of(file);
            }
        }
        for (var fileName : PROJECT_FILES) {
            var file = workspace.resolve(fileName);
            if (Files.isRegularFile(file)) {
                return List.of(file);
            }
        }
        return List.of();
    }
}
