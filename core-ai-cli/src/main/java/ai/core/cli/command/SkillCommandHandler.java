package ai.core.cli.command;

import ai.core.cli.remote.RemoteApiClient;
import ai.core.cli.remote.RemoteConfig;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles /skill command with interactive menu.
 * Lists local skills and provides access to core-ai-server skills.
 *
 * @author stephen
 */
public class SkillCommandHandler {

    private static final String[] SKILL_DIRS = {
        ".core-ai/skills",
        System.getProperty("user.home") + "/.core-ai/skills"
    };
    private static final String SERVER_ENTRY = "core-ai-server (list and install skills from server)";
    private static final Path USER_SKILLS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "skills");

    private static String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private final TerminalUI ui;

    public SkillCommandHandler(TerminalUI ui) {
        this.ui = ui;
    }

    public void handle() {
        var localSkills = scanSkills();
        var labels = new ArrayList<String>();
        for (var skill : localSkills) {
            labels.add(skill.name + AnsiTheme.MUTED + " (" + skill.source + ")" + AnsiTheme.RESET);
        }
        labels.add(SERVER_ENTRY);

        ui.printStreamingChunk(String.format("%n  %sSkills (%d local)%s%n", AnsiTheme.PROMPT, localSkills.size(), AnsiTheme.RESET));

        int selected = ui.pickIndex(labels);
        if (selected < 0) return;

        if (selected < localSkills.size()) {
            loadLocalSkill(localSkills.get(selected));
        } else {
            handleServerSkills();
        }
    }

    public String loadSkillContent(String name) {
        for (var skill : scanSkills()) {
            if (skill.name.equals(name)) return loadSkillContentFromEntry(skill);
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET
                + " Skill '" + name + "' not found.\n\n");
        return null;
    }

    private void loadLocalSkill(SkillEntry skill) {
        var actions = List.of("Upload to server", "Back");

        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + skill.name + ":" + AnsiTheme.RESET + "\n");
        int action = ui.pickIndex(actions);
        if (action == 0) {
            uploadToServer(skill);
        }
    }

    private void uploadToServer(SkillEntry skill) {
        var config = RemoteConfig.load();
        if (config == null) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "core-ai-server is not configured." + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Use /remote to connect to a server first." + AnsiTheme.RESET + "\n\n");
            return;
        }

        var skillFile = skill.dirPath.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            ui.showError("SKILL.md not found in " + skill.dirPath);
            return;
        }

        var api = new RemoteApiClient(config.serverUrl(), config.apiKey());
        var files = new LinkedHashMap<String, Path>();
        files.put("skill_file", skillFile);

        var resources = scanResources(skill.dirPath);
        for (var resource : resources) {
            var resourceFile = skill.dirPath.resolve(resource);
            if (Files.isRegularFile(resourceFile)) {
                files.put(resource, resourceFile);
            }
        }

        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Uploading " + skill.name + "..." + AnsiTheme.RESET + "\n");
        try {
            var result = api.postMultipart("/api/skills/upload", files);
            if (result != null) {
                ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "\u2713" + AnsiTheme.RESET
                        + " Uploaded " + skill.name + " to server.\n\n");
            } else {
                ui.showError("upload failed");
            }
        } catch (Exception e) {
            ui.showError("upload failed: " + e.getMessage());
        }
    }

    private String loadSkillContentFromEntry(SkillEntry skill) {
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
            return sb.toString();
        } catch (IOException e) {
            ui.printStreamingChunk(AnsiTheme.ERROR + "  Failed to read skill: " + e.getMessage() + AnsiTheme.RESET + "\n");
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void handleServerSkills() {
        var config = RemoteConfig.load();
        if (config == null) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "core-ai-server is not configured." + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Use /remote to connect to a server first." + AnsiTheme.RESET + "\n\n");
            return;
        }

        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Fetching skills from server..." + AnsiTheme.RESET + "\n");
        var api = new RemoteApiClient(config.serverUrl(), config.apiKey());

        String json;
        try {
            json = api.get("/api/skills");
        } catch (Exception e) {
            ui.showError("failed to fetch skills: " + e.getMessage());
            return;
        }
        if (json == null) {
            ui.showError("failed to fetch skills from server");
            return;
        }

        Map<String, Object> response = JsonUtil.fromJson(Map.class, json);
        var skills = (List<Map<String, Object>>) response.get("skills");
        if (skills == null || skills.isEmpty()) {
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "No skills on server." + AnsiTheme.RESET + "\n\n");
            return;
        }

        var labels = new ArrayList<String>();
        for (var skill : skills) {
            var qualifiedName = (String) skill.get("qualified_name");
            var desc = (String) skill.get("description");
            boolean installed = isInstalled(qualifiedName);
            var sb = new StringBuilder(qualifiedName);
            if (installed) sb.append(AnsiTheme.SUCCESS).append(" (installed)").append(AnsiTheme.RESET);
            if (desc != null && !desc.isBlank()) {
                sb.append(AnsiTheme.MUTED).append(" - ").append(truncate(desc, 40)).append(AnsiTheme.RESET);
            }
            labels.add(sb.toString());
        }

        ui.printStreamingChunk(String.format("%n  %sServer Skills (%d)%s%n", AnsiTheme.PROMPT, skills.size(), AnsiTheme.RESET));
        int selected = ui.pickIndex(labels);
        if (selected < 0) return;

        var selectedSkill = skills.get(selected);
        var qualifiedName = (String) selectedSkill.get("qualified_name");
        var skillId = (String) selectedSkill.get("id");

        if (isInstalled(qualifiedName)) {
            handleInstalledSkill(api, skillId, qualifiedName);
        } else {
            installSkill(api, skillId, qualifiedName);
        }
    }

    private void handleInstalledSkill(RemoteApiClient api, String skillId, String qualifiedName) {
        var actions = List.of("Update (re-download)", "Remove local copy", "Back");
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + qualifiedName + " is already installed:" + AnsiTheme.RESET + "\n");
        int action = ui.pickIndex(actions);
        switch (action) {
            case 0 -> installSkill(api, skillId, qualifiedName);
            case 1 -> removeSkill(qualifiedName);
            default -> { }
        }
    }

    @SuppressWarnings("unchecked")
    private void installSkill(RemoteApiClient api, String skillId, String qualifiedName) {
        String json;
        try {
            json = api.get("/api/skills/" + skillId + "/download");
        } catch (Exception e) {
            ui.showError("failed to download skill: " + e.getMessage());
            return;
        }
        if (json == null) {
            ui.showError("failed to download skill");
            return;
        }

        Map<String, Object> data = JsonUtil.fromJson(Map.class, json);
        var namespace = (String) data.get("namespace");
        var name = (String) data.get("name");
        var content = (String) data.get("content");

        var skillDir = USER_SKILLS_DIR.resolve(namespace).resolve(name);
        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), content, StandardCharsets.UTF_8);

            var resources = (List<Map<String, String>>) data.get("resources");
            if (resources != null) {
                for (var resource : resources) {
                    var path = resource.get("path");
                    var resourceContent = resource.get("content");
                    var resourceFile = skillDir.resolve(path);
                    Files.createDirectories(resourceFile.getParent());
                    Files.writeString(resourceFile, resourceContent, StandardCharsets.UTF_8);
                }
            }

            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "\u2713" + AnsiTheme.RESET
                    + " Installed " + qualifiedName + " to " + skillDir + "\n\n");
        } catch (IOException e) {
            ui.showError("failed to write skill files: " + e.getMessage());
        }
    }

    private void removeSkill(String qualifiedName) {
        var parts = qualifiedName.split("/", 2);
        if (parts.length != 2) return;
        var skillDir = USER_SKILLS_DIR.resolve(parts[0]).resolve(parts[1]);
        if (!Files.exists(skillDir)) {
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Not found locally." + AnsiTheme.RESET + "\n");
            return;
        }
        try (var walk = Files.walk(skillDir)) {
            var paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (var path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            ui.showError("failed to remove: " + e.getMessage());
            return;
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "\u2713" + AnsiTheme.RESET
                + " Removed " + qualifiedName + "\n\n");
    }

    private boolean isInstalled(String qualifiedName) {
        if (qualifiedName == null) return false;
        var parts = qualifiedName.split("/", 2);
        if (parts.length != 2) return false;
        return Files.exists(USER_SKILLS_DIR.resolve(parts[0]).resolve(parts[1]).resolve("SKILL.md"));
    }

    private List<SkillEntry> scanSkills() {
        var result = new ArrayList<SkillEntry>();
        for (String dir : SKILL_DIRS) {
            var path = Path.of(dir);
            if (!Files.isDirectory(path)) continue;
            try (var stream = Files.list(path)) {
                stream.filter(Files::isDirectory)
                      .sorted()
                      .forEach(p -> {
                          var skillMd = p.resolve("SKILL.md");
                          if (Files.isRegularFile(skillMd)) {
                              String name = parseFrontmatterName(skillMd);
                              if (name == null) name = p.getFileName().toString();
                              result.add(new SkillEntry(name, p, dir));
                          } else {
                              scanNamespaceDir(p, dir, result);
                          }
                      });
            } catch (IOException ignored) {
                // skip unreadable directories
            }
        }
        return result;
    }

    private void scanNamespaceDir(Path namespaceDir, String source, List<SkillEntry> result) {
        String namespace = namespaceDir.getFileName().toString();
        try (var stream = Files.list(namespaceDir)) {
            stream.filter(Files::isDirectory)
                  .filter(p -> Files.isRegularFile(p.resolve("SKILL.md")))
                  .sorted()
                  .forEach(p -> {
                      String name = parseFrontmatterName(p.resolve("SKILL.md"));
                      if (name == null) name = p.getFileName().toString();
                      result.add(new SkillEntry(namespace + "/" + name, p, source));
                  });
        } catch (IOException ignored) {
            // skip unreadable namespace directories
        }
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
