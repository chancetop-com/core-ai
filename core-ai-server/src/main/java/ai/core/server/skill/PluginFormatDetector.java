package ai.core.server.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-detects plugin formats (Claude Code, Codex CLI, etc.) in a cloned
 * repository by reading their configuration files and returns the relative
 * skill directory path(s).
 * <p>
 * This is format-level detection, not filesystem scanning — it reads
 * known configuration files (plugin.json, marketplace.json) to find
 * where skills are located.
 *
 * @author core-ai-cli
 */
public class PluginFormatDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(PluginFormatDetector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String getTextValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null ? value.asText(null) : null;
    }

    /**
     * Detect whether the repository root has a known plugin format marker.
     */
    public boolean hasPluginFormat(Path repoDir) {
        return Files.exists(repoDir.resolve(".claude-plugin"))
            || Files.isDirectory(repoDir.resolve(".agents").resolve("plugins"))
            || Files.isRegularFile(repoDir.resolve("SKILL.md"));
    }

    /**
     * Skill directories declared by the repository plugin manifest. A Claude Code plugin may declare
     * {@code "skills"} as one path or as an array of paths; both forms are part of the format, and a
     * multi-root plugin (e.g. a shared {@code ads/} root plus {@code skills/}) only imports correctly
     * when every declared root is read.
     */
    public List<String> declaredSkillPaths(Path repoDir) {
        var root = repoDir.normalize();
        var paths = new ArrayList<String>();
        for (var raw : manifestSkillPaths(root.resolve(".claude-plugin").resolve("plugin.json"))) {
            addIfPresent(paths, root, root, raw);
        }
        for (var raw : manifestSkillPaths(root.resolve("plugin.json"))) {
            addIfPresent(paths, root, root, raw);
        }
        return distinct(paths);
    }

    /**
     * Detect plugin format and return the relative skill directory paths.
     * <p>
     * Detection order:
     * <ol>
     *   <li>Claude Code {@code plugin.json} → {@code "skills"} field (string or array), read from
     *       {@code .claude-plugin/plugin.json} or the repository root</li>
     *   <li>Claude Code {@code .claude-plugin/marketplace.json} → plugin sources → skill dirs</li>
     *   <li>Codex CLI {@code .agents/plugins/marketplace.json} → plugin sources</li>
     *   <li>Common conventions: {@code .claude/skills/}</li>
     *   <li>Single-skill repo: {@code SKILL.md} at repository root (skills.sh convention)</li>
     * </ol>
     * <p>
     * Each returned path is relative to {@code repoDir} and can be passed to
     * {@code SkillLoader.loadFromSource()}.
     *
     * @param repoDir the root of the cloned repository
     * @return list of relative skill directory paths, or empty list if no format detected
     */
    public List<String> detectSkillPaths(Path repoDir) {
        // 1. Try Claude Code plugin.json format
        List<String> paths = declaredSkillPaths(repoDir);
        if (!paths.isEmpty()) {
            LOGGER.info("Detected Claude Code plugin.json format in {}", repoDir);
            return paths;
        }

        // 2. Try Claude Code marketplace.json format
        paths = detectClaudeCodeMarketplaceFormat(repoDir);
        if (!paths.isEmpty()) {
            LOGGER.info("Detected Claude Code marketplace.json format in {}", repoDir);
            return paths;
        }

        // 3. Try Codex CLI format
        paths = detectCodexCliFormat(repoDir);
        if (!paths.isEmpty()) {
            LOGGER.info("Detected Codex CLI plugin format in {}", repoDir);
            return paths;
        }

        // 4. Common convention: .claude/skills/
        Path claudeSkills = repoDir.resolve(".claude").resolve("skills");
        if (Files.isDirectory(claudeSkills)) {
            LOGGER.info("Detected .claude/skills/ convention in {}", repoDir);
            return List.of(".claude/skills");
        }

        // 5. Single-skill repo: SKILL.md at repository root (skills.sh convention)
        if (Files.isRegularFile(repoDir.resolve("SKILL.md"))) {
            LOGGER.info("Detected root-level SKILL.md format in {}", repoDir);
            return List.of(".");
        }

        return List.of();
    }

    /** The {@code skills} values of one plugin manifest, accepting both the string and the array form. */
    private List<String> manifestSkillPaths(Path manifest) {
        if (!Files.isRegularFile(manifest)) return List.of();
        try {
            return textValues(MAPPER.readTree(manifest.toFile()).get("skills"));
        } catch (IOException e) {
            LOGGER.warn("Failed to parse {}", manifest, e);
            return List.of();
        }
    }

    private List<String> textValues(JsonNode node) {
        if (node == null) return List.of();
        if (node.isTextual()) return List.of(node.asText());
        if (!node.isArray()) return List.of();
        var values = new ArrayList<String>();
        for (var item : node) {
            if (item.isTextual()) values.add(item.asText());
        }
        return values;
    }

    private void addIfPresent(List<String> paths, Path repoDir, Path baseDir, String raw) {
        if (raw == null || raw.isBlank()) return;
        Path resolved;
        try {
            resolved = baseDir.resolve(raw.trim()).normalize();
        } catch (RuntimeException e) {
            LOGGER.warn("Ignoring invalid skill path '{}' in {}", raw, baseDir);
            return;
        }
        if (!resolved.startsWith(repoDir)) {
            LOGGER.warn("Ignoring skill path '{}' that escapes the repository: {}", raw, resolved);
            return;
        }
        if (!Files.isDirectory(resolved)) {
            LOGGER.warn("Ignoring skill path '{}' that is not a directory: {}", raw, resolved);
            return;
        }
        paths.add(relativePath(repoDir, resolved));
    }

    /**
     * Claude Code format: .claude-plugin/marketplace.json → plugins[*].source → skill dirs
     */
    private List<String> detectClaudeCodeMarketplaceFormat(Path repoDir) {
        Path marketplaceJsonPath = repoDir.resolve(".claude-plugin").resolve("marketplace.json");
        if (!Files.exists(marketplaceJsonPath)) return List.of();

        try {
            JsonNode root = MAPPER.readTree(marketplaceJsonPath.toFile());
            JsonNode plugins = root.get("plugins");
            if (plugins != null && plugins.isArray()) {
                List<String> dirs = new ArrayList<>();
                for (JsonNode plugin : plugins) {
                    // Each plugin may have a "source" field pointing to a plugin directory
                    String source = getTextValue(plugin, "source");
                    if (source != null && !source.isBlank()) {
                        // Check if this plugin directory itself has a plugin.json with skill path
                        dirs.addAll(resolvePluginSkillPath(repoDir, source));
                    }
                }
                if (!dirs.isEmpty()) return distinct(dirs);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to parse .claude-plugin/marketplace.json", e);
        }
        return List.of();
    }

    /**
     * Codex CLI format: .agents/plugins/marketplace.json → plugin sources
     */
    private List<String> detectCodexCliFormat(Path repoDir) {
        Path marketplaceJsonPath = repoDir.resolve(".agents").resolve("plugins").resolve("marketplace.json");
        if (!Files.exists(marketplaceJsonPath)) return List.of();

        try {
            JsonNode root = MAPPER.readTree(marketplaceJsonPath.toFile());
            JsonNode plugins = root.get("plugins");
            if (plugins != null && plugins.isArray()) {
                List<String> dirs = new ArrayList<>();
                for (JsonNode plugin : plugins) {
                    String source = getTextValue(plugin, "source");
                    if (source != null && !source.isBlank()) {
                        dirs.addAll(resolvePluginSkillPath(repoDir, source));
                    }
                }
                if (!dirs.isEmpty()) return distinct(dirs);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to parse .agents/plugins/marketplace.json", e);
        }
        return List.of();
    }

    /**
     * Resolve a plugin source directory to find its skill paths.
     * Checks for a nested plugin manifest, then common conventions.
     */
    private List<String> resolvePluginSkillPath(Path repoDir, String source) {
        Path pluginDir = repoDir.resolve(source).normalize();
        if (!pluginDir.startsWith(repoDir.normalize()) || !Files.isDirectory(pluginDir)) {
            LOGGER.warn("Ignoring plugin source '{}' that is not a directory inside the repository", source);
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (var manifest : List.of(pluginDir.resolve("plugin.json"), pluginDir.resolve(".claude-plugin").resolve("plugin.json"))) {
            for (var raw : manifestSkillPaths(manifest)) {
                addIfPresent(paths, repoDir.normalize(), pluginDir, raw);
            }
        }
        paths.addAll(resolveConventionDirs(repoDir, pluginDir));
        return distinct(paths);
    }

    /**
     * Collect all conventional skill directories under a plugin directory.
     * A plugin can hold skills in multiple locations (e.g. MiniMax-AI/skills keeps
     * skills in both .claude/skills and skills/), so every match is returned.
     */
    private List<String> resolveConventionDirs(Path repoDir, Path pluginDir) {
        List<String> paths = new ArrayList<>();
        Path claudeSkills = pluginDir.resolve(".claude").resolve("skills");
        if (Files.isDirectory(claudeSkills)) {
            paths.add(relativePath(repoDir, claudeSkills));
        }
        Path skills = pluginDir.resolve("skills");
        if (Files.isDirectory(skills)) {
            paths.add(relativePath(repoDir, skills));
        }
        return paths;
    }

    private String relativePath(Path repoDir, Path target) {
        var relative = repoDir.normalize().relativize(target);
        if (relative.toString().isEmpty()) return ".";
        // Normalize separators so persisted skill paths are portable across platforms
        return relative.toString().replace('\\', '/');
    }

    private List<String> distinct(List<String> paths) {
        return paths.stream().distinct().toList();
    }
}
