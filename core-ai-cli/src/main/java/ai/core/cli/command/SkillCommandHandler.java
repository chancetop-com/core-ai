package ai.core.cli.command;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles /skill command to list available skills from skill directories.
 *
 * @author xander
 */
public class SkillCommandHandler {

    private static final String[] SKILL_DIRS = {
        ".core-ai/skills",
        System.getProperty("user.home") + "/.core-ai/skills"
    };

    private final TerminalUI ui;

    public SkillCommandHandler(TerminalUI ui) {
        this.ui = ui;
    }

    public void handle() {
        var skills = scanSkills();
        if (skills.isEmpty()) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "No skills found." + AnsiTheme.RESET);
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Create skill directories with SKILL.md in .core-ai/skills/" + AnsiTheme.RESET + "\n\n");
            return;
        }
        ui.printStreamingChunk(String.format("%n  %sAvailable Skills (%d)%s%n", AnsiTheme.PROMPT, skills.size(), AnsiTheme.RESET));
        for (var skill : skills) {
            ui.printStreamingChunk("  " + AnsiTheme.CMD_NAME + skill.name + AnsiTheme.RESET
                    + AnsiTheme.MUTED + " (" + skill.source + ")" + AnsiTheme.RESET + "\n");
        }
        ui.printStreamingChunk("\n");
    }

    private List<SkillEntry> scanSkills() {
        var result = new ArrayList<SkillEntry>();
        for (String dir : SKILL_DIRS) {
            var path = Path.of(dir);
            if (!Files.isDirectory(path)) continue;
            try (var stream = Files.list(path)) {
                stream.filter(Files::isDirectory)
                      .filter(p -> Files.isRegularFile(p.resolve("SKILL.md")))
                      .sorted()
                      .forEach(p -> {
                          String name = p.getFileName().toString();
                          result.add(new SkillEntry(name, dir));
                      });
            } catch (IOException ignored) {
                // skip unreadable directories
            }
        }
        return result;
    }

    private record SkillEntry(String name, String source) {
    }
}
