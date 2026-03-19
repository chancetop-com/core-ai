package ai.core.cli.command;

import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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

    public String loadSkillContent(String name) {
        for (var skill : scanSkills()) {
            if (!skill.name.equals(name)) continue;
            var skillFile = skill.dirPath.resolve("SKILL.md");
            try {
                String skillMd = Files.readString(skillFile, StandardCharsets.UTF_8);
                var sb = new StringBuilder(skillMd.length() + 256);
                sb.append("<skill name=\"").append(skill.name)
                        .append("\" base_dir=\"").append(skill.dirPath.toAbsolutePath())
                        .append("\">\n")
                        .append(skillMd);
                List<String> resources = scanResources(skill.dirPath);
                if (!resources.isEmpty()) {
                    sb.append("\n\nResources:\n");
                    for (String resource : resources) {
                        sb.append("- ").append(resource).append('\n');
                    }
                }
                sb.append("</skill>");
                ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                        + " Skill '" + skill.name + "' loaded into conversation.\n\n");
                return sb.toString();
            } catch (IOException e) {
                ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to read skill: " + e.getMessage() + AnsiTheme.RESET + "\n");
                return null;
            }
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET
                + " Skill '" + name + "' not found.\n\n");
        return null;
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
                          String name = parseFrontmatterName(p.resolve("SKILL.md"));
                          if (name == null) name = p.getFileName().toString();
                          result.add(new SkillEntry(name, p, dir));
                      });
            } catch (IOException ignored) {
                // skip unreadable directories
            }
        }
        return result;
    }

    private String parseFrontmatterName(Path skillFile) {
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            if (!content.startsWith("---")) return null;
            int end = content.indexOf("\n---", 3);
            if (end < 0) return null;
            for (String line : content.substring(3, end).split("\n")) {
                if (line.startsWith("name:")) return line.substring(5).trim();
            }
        } catch (IOException ignored) {
            // fall back to directory name
        }
        return null;
    }

    private List<String> scanResources(Path skillDir) {
        String[] subDirs = {"scripts", "references"};
        var result = new ArrayList<String>();
        for (String sub : subDirs) {
            Path subDir = skillDir.resolve(sub);
            if (!Files.isDirectory(subDir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(subDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        result.add(sub + "/" + entry.getFileName().toString());
                    }
                }
            } catch (IOException ignored) {
                // skip
            }
        }
        result.sort(String::compareTo);
        return result;
    }

    private record SkillEntry(String name, Path dirPath, String source) {
    }
}
