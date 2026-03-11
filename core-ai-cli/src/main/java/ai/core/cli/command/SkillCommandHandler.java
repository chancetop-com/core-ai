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
import java.util.Properties;

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
        for (String dir : SKILL_DIRS) {
            var skillDir = Path.of(dir, name);
            var skillFile = skillDir.resolve("SKILL.md");
            if (!Files.isRegularFile(skillFile)) continue;
            try {
                String skillMd = Files.readString(skillFile, StandardCharsets.UTF_8);
                var sb = new StringBuilder(skillMd.length() + 256);
                sb.append("<skill name=\"").append(name)
                        .append("\" base_dir=\"").append(skillDir.toAbsolutePath())
                        .append("\">\n")
                        .append(skillMd);
                List<String> refFiles = scanResourceFiles(skillDir);
                if (!refFiles.isEmpty()) {
                    sb.append("\n\nReferences:\n");
                    for (String file : refFiles) {
                        sb.append("- ").append(file).append('\n');
                    }
                }
                sb.append("</skill>");
                ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
                        + " Skill '" + name + "' loaded into conversation.\n\n");
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

    private List<String> scanResourceFiles(Path skillDir) {
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

    public void handleConfig(String args) {
        Path configFile = Path.of(".core-ai", "skill-config.properties");
        if (args == null || args.isBlank()) {
            showConfig(configFile);
            return;
        }
        String[] parts = args.trim().split("\\s+", 2);
        if (parts.length < 2) {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "Usage: /skill config <key> <value>" + AnsiTheme.RESET);
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Example: /skill config recommend on|off" + AnsiTheme.RESET + "\n\n");
            return;
        }
        String key = parts[0];
        if ("recommend".equals(key)) {
            boolean enabled = "on".equalsIgnoreCase(parts[1]) || "true".equalsIgnoreCase(parts[1]);
            saveConfig(configFile, "recommendEnabled", enabled);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "Skill recommendation " + (enabled ? "enabled" : "disabled")
                    + AnsiTheme.RESET + "\n\n");
        } else {
            ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "Unknown config key: " + key + AnsiTheme.RESET + "\n\n");
        }
    }

    private void showConfig(Path configFile) {
        Properties props = loadConfigFile(configFile);
        ui.printStreamingChunk(String.format("%n  %sSkill Config%s%n", AnsiTheme.PROMPT, AnsiTheme.RESET));
        boolean recommend = "true".equals(props.getProperty("recommendEnabled", "false"));
        ui.printStreamingChunk("  recommend: " + (recommend ? "on" : "off") + "\n\n");
    }

    private void saveConfig(Path configFile, String key, Object value) {
        Properties props = loadConfigFile(configFile);
        props.setProperty(key, String.valueOf(value));
        try {
            Files.createDirectories(configFile.getParent());
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to save config: " + e.getMessage() + AnsiTheme.RESET + "\n");
            return;
        }
        try (var out = Files.newOutputStream(configFile)) {
            props.store(out, "skill config");
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to save config: " + e.getMessage() + AnsiTheme.RESET + "\n");
        }
    }

    private Properties loadConfigFile(Path configFile) {
        var props = new Properties();
        if (Files.isRegularFile(configFile)) {
            try (var in = Files.newInputStream(configFile)) {
                props.load(in);
            } catch (IOException ignored) {
                // use defaults
            }
        }
        return props;
    }

    private record SkillEntry(String name, String source) {
    }
}
