package ai.core.cli.command;

import ai.core.cli.auth.AuthConfig;
import ai.core.cli.hub.skill.LocalSkillScanner;
import ai.core.cli.hub.skill.SkillHubClient;
import ai.core.cli.hub.skill.SkillHubMarker;
import ai.core.cli.hub.skill.SkillInstaller;
import ai.core.cli.hub.skill.SkillLocations;
import ai.core.cli.ui.AnsiTheme;
import ai.core.cli.ui.TerminalUI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Handles /skill command with interactive menu. Lists local skills (scanned with
 * {@link LocalSkillScanner}, the same parser the agent stack uses) and provides
 * access to the core-ai-server skill hub (search/show/pull/remove through
 * {@link SkillHubClient} + {@link SkillInstaller}).
 *
 * @author stephen
 */
public class SkillCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillCommandHandler.class);
    private static final String SERVER_ENTRY = "core-ai-server (list and install skills from server)";

    private static String truncate(String text, int max) {
        if (text == null) return "";
        var clean = text.replaceAll("[\\r\\n]+", " ").strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private final TerminalUI ui;
    private final LocalSkillScanner scanner = new LocalSkillScanner();

    public SkillCommandHandler(TerminalUI ui) {
        this.ui = ui;
    }

    public void handle() {
        var localSkills = scanLocalSkills();
        var labels = new ArrayList<String>();
        for (var skill : localSkills) {
            var sb = new StringBuilder(displayName(skill));
            if (skill.description() != null && !skill.description().isBlank()) {
                sb.append(AnsiTheme.MUTED).append(" - ").append(truncate(skill.description(), 40)).append(AnsiTheme.RESET);
            }
            labels.add(sb.toString());
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
        for (var skill : scanLocalSkills()) {
            if (name.equals(skill.name()) || name.equals(displayName(skill))) return loadSkillContentFromEntry(skill);
        }
        ui.printStreamingChunk("\n  " + AnsiTheme.WARNING + "!" + AnsiTheme.RESET
                + " Skill '" + name + "' not found.\n\n");
        return null;
    }

    private void loadLocalSkill(LocalSkillScanner.LocalSkill skill) {
        var actions = List.of("Upload to server", "Back");

        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + displayName(skill) + ":" + AnsiTheme.RESET + "\n");
        int action = ui.pickIndex(actions);
        if (action == 0) {
            uploadToServer(skill);
        }
    }

    private void uploadToServer(LocalSkillScanner.LocalSkill skill) {
        var client = authenticatedClient();
        if (client == null) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "core-ai-server is not configured." + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Run core-ai-cli and use /login to connect to a server first." + AnsiTheme.RESET + "\n\n");
            return;
        }

        var skillFile = skill.skillDir().resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) {
            ui.showError("SKILL.md not found in " + skill.skillDir());
            return;
        }

        var files = new LinkedHashMap<String, Path>();
        files.put("skill_file", skillFile);
        for (var resource : skill.resources()) {
            var resourceFile = skill.skillDir().resolve(resource);
            if (Files.isRegularFile(resourceFile)) {
                files.put(resource, resourceFile);
            }
        }

        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Uploading " + skill.name() + "..." + AnsiTheme.RESET + "\n");
        try {
            var result = client.push(files);
            if (result != null) {
                ui.printStreamingChunk("  " + AnsiTheme.SUCCESS + "\u2713" + AnsiTheme.RESET
                        + " Uploaded " + skill.name() + " to server.\n\n");
            } else {
                ui.showError("upload failed");
            }
        } catch (Exception e) {
            ui.showError("upload failed: " + e.getMessage());
        }
    }

    private String loadSkillContentFromEntry(LocalSkillScanner.LocalSkill skill) {
        var skillFile = skill.skillDir().resolve("SKILL.md");
        try {
            String skillMd = Files.readString(skillFile, StandardCharsets.UTF_8);
            var sb = new StringBuilder(skillMd.length() + 256);
            sb.append("<skill name=\"").append(skill.name())
                    .append("\" base_dir=\"").append(skill.skillDir().toAbsolutePath())
                    .append("\">\n")
                    .append(skillMd);
            if (!skill.resources().isEmpty()) {
                sb.append("\n\nResources:\n");
                for (String resource : skill.resources()) {
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

    private void handleServerSkills() {
        var client = authenticatedClient();
        if (client == null) {
            ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "core-ai-server is not configured." + AnsiTheme.RESET + "\n");
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "Run core-ai-cli and use /login to connect to a server first." + AnsiTheme.RESET + "\n\n");
            return;
        }

        ui.printStreamingChunk("\n  " + AnsiTheme.MUTED + "Fetching skills from server..." + AnsiTheme.RESET + "\n");
        ai.core.api.server.skillhub.SkillHubSearchResponse response;
        try {
            response = client.search(null, null, null, 200);
        } catch (Exception e) {
            ui.showError("failed to fetch skills: " + e.getMessage());
            return;
        }
        var skills = response.skills == null ? List.<ai.core.api.server.skillhub.SkillHubSummary>of() : response.skills;
        if (skills.isEmpty()) {
            ui.printStreamingChunk("  " + AnsiTheme.MUTED + "No skills on server." + AnsiTheme.RESET + "\n\n");
            return;
        }

        var labels = new ArrayList<String>();
        for (var skill : skills) {
            boolean installed = isInstalledLocally(skill.qualifiedName);
            var sb = new StringBuilder(skill.qualifiedName);
            if (installed) sb.append(AnsiTheme.SUCCESS).append(" (installed)").append(AnsiTheme.RESET);
            if (skill.description != null && !skill.description.isBlank()) {
                sb.append(AnsiTheme.MUTED).append(" - ").append(truncate(skill.description, 40)).append(AnsiTheme.RESET);
            }
            labels.add(sb.toString());
        }

        ui.printStreamingChunk(String.format("%n  %sServer Skills (%d)%s%n", AnsiTheme.PROMPT, skills.size(), AnsiTheme.RESET));
        int selected = ui.pickIndex(labels);
        if (selected < 0) return;

        var selectedSkill = skills.get(selected);
        if (isInstalledLocally(selectedSkill.qualifiedName)) {
            handleInstalledSkill(client, selectedSkill);
        } else {
            installSkill(client, selectedSkill, true);
        }
    }

    private void handleInstalledSkill(SkillHubClient client, ai.core.api.server.skillhub.SkillHubSummary skill) {
        var actions = List.of("Update (re-download)", "Remove local copy", "Back");
        ui.printStreamingChunk("\n  " + AnsiTheme.PROMPT + skill.qualifiedName + " is already installed:" + AnsiTheme.RESET + "\n");
        int action = ui.pickIndex(actions);
        switch (action) {
            case 0 -> installSkill(client, skill, true);
            case 1 -> removeSkill(skill.qualifiedName);
            default -> {
            }
        }
    }

    private void installSkill(SkillHubClient client, ai.core.api.server.skillhub.SkillHubSummary skill, boolean force) {
        try {
            var archive = client.archive(skill.namespace, skill.name);
            var targetDir = SkillLocations.userSkillsDir().resolve(skill.namespace).resolve(skill.name);
            var source = new SkillHubMarker.Marker(skill.qualifiedName, archive.id() != null ? archive.id() : skill.id,
                    archive.digest() != null ? archive.digest() : "", client.serverUrl(), null);
            var outcome = new SkillInstaller().install(targetDir, archive.bytes(), source, force);
            ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "\u2713" + AnsiTheme.RESET
                    + (outcome.replaced() ? " Installed " : " Already up to date: ")
                    + skill.qualifiedName + " to " + targetDir + "\n\n");
        } catch (Exception e) {
            ui.showError("failed to install skill: " + e.getMessage());
        }
    }

    private void removeSkill(String qualifiedName) {
        var parts = qualifiedName.split("/", 2);
        if (parts.length != 2) return;
        var skillDir = SkillLocations.userSkillsDir().resolve(parts[0]).resolve(parts[1]);
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

    private boolean isInstalledLocally(String qualifiedName) {
        if (qualifiedName == null) return false;
        var parts = qualifiedName.split("/", 2);
        if (parts.length != 2) return false;
        for (var root : List.of(Path.of(".core-ai/skills"), SkillLocations.userSkillsDir())) {
            var dir = root.resolve(parts[0]).resolve(parts[1]);
            if (Files.isRegularFile(dir.resolve("SKILL.md"))) return true;
        }
        return false;
    }

    private List<LocalSkillScanner.LocalSkill> scanLocalSkills() {
        var result = new ArrayList<LocalSkillScanner.LocalSkill>();
        for (var root : List.of(Path.of(".core-ai/skills"), SkillLocations.userSkillsDir())) {
            if (!Files.isDirectory(root)) continue;
            try {
                result.addAll(scanner.scan(root));
            } catch (Exception e) {
                LOGGER.debug("failed to scan skills directory: {}", root, e);
            }
        }
        return result;
    }

    private String displayName(LocalSkillScanner.LocalSkill skill) {
        if (skill.marker() != null && skill.marker().qualifiedName() != null) return skill.marker().qualifiedName();
        String qualified = skill.qualifiedName();
        return qualified == null || qualified.isEmpty() ? skill.name() : qualified;
    }

    private SkillHubClient authenticatedClient() {
        var auth = AuthConfig.load();
        if (auth == null || auth.serverUrl() == null || auth.apiKey() == null) return null;
        return new SkillHubClient(auth.serverUrl(), auth.apiKey());
    }
}
