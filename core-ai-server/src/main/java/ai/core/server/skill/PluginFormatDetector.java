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

    /**
     * Detect whether the repository root has a known plugin format marker.
     */
    public boolean hasPluginFormat(Path repoDir) {
        return Files.exists(repoDir.resolve(".claude-plugin"))
            || Files.isDirectory(repoDir.resolve(".agents").resolve("plugins"));
    }

    /**
     * Detect plugin format and return the relative skill directory paths.
     * <p>
     * Detection order:
     * <ol>
     *   <li>Claude Code {@code .claude-plugin/plugin.json} → {@code "skills"} field</li>
     *   <li>Claude Code {@code .claude-plugin/marketplace.json} → plugin sources → skill dirs</li>
     *   <li>Codex CLI {@code .agents/plugins/marketplace.json} → plugin sources</li>
     *   <li>Common conventions: {@code .claude/skills/}</li>
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
        List<String> paths = detectClaudeCodePluginFormat(repoDir);
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

        return List.of();
    }

    /**
     * Claude Code format: .claude-plugin/plugin.json → "skills" field
     */
    private List<String> detectClaudeCodePluginFormat(Path repoDir) {
        Path pluginJsonPath = repoDir.resolve(".claude-plugin").resolve("plugin.json");
        if (!Files.exists(pluginJsonPath)) return List.of();

        try {
            JsonNode node = MAPPER.readTree(pluginJsonPath.toFile());
            String skillsPath = getTextValue(node, "skills");
            if (skillsPath != null && !skillsPath.isBlank()) {
                return List.of(skillsPath);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to parse .claude-plugin/plugin.json", e);
        }
        return List.of();
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
                        resolvePluginSkillPath(repoDir, source).ifPresent(dirs::add);
                    }
                }
                if (!dirs.isEmpty()) return dirs;
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
                        resolvePluginSkillPath(repoDir, source).ifPresent(dirs::add);
                    }
                }
                if (!dirs.isEmpty()) return dirs;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to parse .agents/plugins/marketplace.json", e);
        }
        return List.of();
    }

    /**
     * Resolve a plugin source directory to find its skill path.
     * Checks for nested plugin.json, then common conventions.
     */
    private java.util.Optional<String> resolvePluginSkillPath(Path repoDir, String source) {
        Path pluginDir = repoDir.resolve(source).normalize();

        // Check for nested plugin.json with "skills" field
        Path nestedPluginJson = pluginDir.resolve("plugin.json");
        if (Files.exists(nestedPluginJson)) {
            try {
                JsonNode nested = MAPPER.readTree(nestedPluginJson.toFile());
                String skillsPath = getTextValue(nested, "skills");
                if (skillsPath != null && !skillsPath.isBlank()) {
                    // Resolve relative to plugin directory
                    Path resolved = pluginDir.resolve(skillsPath).normalize();
                    if (Files.isDirectory(resolved)) {
                        return java.util.Optional.of(repoDir.relativize(resolved).toString());
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to parse plugin.json in {}", pluginDir, e);
            }
        }

        // Check for .claude/skills/ relative to plugin directory
        Path claudeSkills = pluginDir.resolve(".claude").resolve("skills");
        if (Files.isDirectory(claudeSkills)) {
            return java.util.Optional.of(repoDir.relativize(claudeSkills).toString());
        }

        // Check for skills/ relative to plugin directory
        Path skills = pluginDir.resolve("skills");
        if (Files.isDirectory(skills)) {
            return java.util.Optional.of(repoDir.relativize(skills).toString());
        }

        return java.util.Optional.empty();
    }

    private static String getTextValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null ? value.asText(null) : null;
    }
}
