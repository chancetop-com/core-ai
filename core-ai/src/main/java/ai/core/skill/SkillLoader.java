package ai.core.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author xander
 */
public class SkillLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL);

    private final int maxSkillFileSize;

    public SkillLoader(int maxSkillFileSize) {
        this.maxSkillFileSize = maxSkillFileSize;
    }

    public List<SkillMetadata> loadAll(List<SkillSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, SkillMetadata> skillMap = new LinkedHashMap<>();
        var sorted = new ArrayList<>(sources);
        Collections.sort(sorted);
        for (var source : sorted) {
            var skills = loadFromSource(source.path());
            for (var skill : skills) {
                skillMap.put(skill.getQualifiedName(), skill);
            }
        }
        return new ArrayList<>(skillMap.values());
    }

    public List<SkillMetadata> loadFromSource(String sourcePath) {
        var dir = Path.of(sourcePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            LOGGER.debug("Skill source directory does not exist: {}", sourcePath);
            return Collections.emptyList();
        }

        Path realDir = resolveRealPath(dir);
        if (realDir == null) return Collections.emptyList();

        List<SkillMetadata> skills = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(realDir)) {
            for (var entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                var skillFile = entry.resolve(SKILL_FILE_NAME);
                if (Files.exists(skillFile)) {
                    if (!isWithinDirectory(skillFile, realDir)) {
                        LOGGER.warn("Skill file path escapes source directory: {}", skillFile);
                        continue;
                    }
                    var skill = loadSkillFile(skillFile, entry.getFileName().toString(), null);
                    if (skill != null) {
                        skills.add(skill);
                    }
                } else {
                    skills.addAll(loadNamespaceDirectory(entry, realDir));
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan skill source directory: {}", sourcePath, e);
        }
        return skills;
    }

    private List<SkillMetadata> loadNamespaceDirectory(Path namespaceDir, Path rootDir) {
        String namespace = namespaceDir.getFileName().toString();
        List<SkillMetadata> skills = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(namespaceDir)) {
            for (var entry : stream) {
                if (!Files.isDirectory(entry)) continue;
                var skillFile = entry.resolve(SKILL_FILE_NAME);
                if (!Files.exists(skillFile)) continue;
                if (!isWithinDirectory(skillFile, rootDir)) {
                    LOGGER.warn("Skill file path escapes source directory: {}", skillFile);
                    continue;
                }
                var skill = loadSkillFile(skillFile, entry.getFileName().toString(), namespace);
                if (skill != null) {
                    skills.add(skill);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan namespace directory: {}", namespaceDir, e);
        }
        return skills;
    }

    private Path resolveRealPath(Path dir) {
        try {
            return dir.toRealPath();
        } catch (IOException e) {
            LOGGER.warn("Failed to resolve real path: {}", dir, e);
            return null;
        }
    }

    // Parse symlinks to prevent... /.. Attacks like /etc/passwd
    private boolean isWithinDirectory(Path file, Path directory) {
        try {
            return file.toRealPath().startsWith(directory);
        } catch (IOException e) {
            return false;
        }
    }

    private SkillMetadata loadSkillFile(Path skillFile, String directoryName, String namespace) {
        try {
            byte[] bytes = Files.readAllBytes(skillFile);
            if (bytes.length > maxSkillFileSize) {
                LOGGER.warn("Skill file exceeds max size ({}): {}", maxSkillFileSize, skillFile);
                return null;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            Path skillDir = skillFile.getParent();
            List<String> resources = scanResources(skillDir);
            SkillMetadata base = parseSkillMd(content, skillFile.toAbsolutePath().toString(), directoryName);
            if (base == null) return null;
            return SkillMetadata.builder(base.getName(), base.getDescription(), base.getPath())
                    .namespace(namespace)
                    .skillDir(skillDir.toAbsolutePath().toString())
                    .resources(resources)
                    .license(base.getLicense())
                    .compatibility(base.getCompatibility())
                    .metadata(base.getMetadata())
                    .allowedTools(base.getAllowedTools())
                    .build();
        } catch (IOException e) {
            LOGGER.warn("Failed to read skill file: {}", skillFile, e);
            return null;
        }
    }

    List<String> scanResources(Path skillDir) {
        List<String> result = new ArrayList<>();
        try (var walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile).forEach(f -> {
                var rel = skillDir.relativize(f).toString().replace('\\', '/');
                if (rel.isEmpty() || "SKILL.md".equals(rel)) return;
                if (rel.startsWith(".") || rel.contains("/.")) return;
                result.add(rel);
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to scan resources in {}: {}", skillDir, e.getMessage());
        }
        Collections.sort(result);
        return result;
    }

    public SkillMetadata parseSkillMd(String content, String filePath, String directoryName) {
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            LOGGER.warn("No YAML frontmatter in {}", filePath);
            return null;
        }
        try {
            var options = new LoaderOptions();
            //todo Prevent YAML Bomb
            //todo skill counts
            options.setCodePointLimit(maxSkillFileSize);
            // only allow String/Map/List avoid Deserialization attack
            var yaml = new Yaml(new SafeConstructor(options));
            Map<String, Object> data = yaml.load(matcher.group(1));
            if (data == null) {
                LOGGER.warn("Empty YAML frontmatter in {}", filePath);
                return null;
            }

            String name = (String) data.get("name");
            String description = (String) data.get("description");
            if (name == null || description == null) {
                LOGGER.warn("Missing name or description in {}", filePath);
                return null;
            }
            if (!validateSkillName(name, directoryName)) {
                LOGGER.warn("Invalid skill name '{}' in {}, must match directory name '{}'", name, filePath, directoryName);
                return null;
            }

            String license = (String) data.get("license");
            String compatibility = (String) data.get("compatibility");
            Map<String, String> metadata = parseMetadata(data.get("metadata"));
            List<String> allowedTools = parseAllowedTools(data.get("allowed-tools"));

            return SkillMetadata.builder(name, description, filePath)
                    .license(license)
                    .compatibility(compatibility)
                    .metadata(metadata)
                    .allowedTools(allowedTools)
                    .build();
        } catch (Exception e) {
            LOGGER.warn("Failed to parse YAML frontmatter in {}", filePath, e);
            return null;
        }
    }

    boolean validateSkillName(String name, String directoryName) {
        if (name == null || name.isBlank()) return false;
        if (!SkillMetadata.isValidName(name)) {
            LOGGER.warn("Skill name '{}' does not follow naming convention (expected: ^[a-z0-9]+(-[a-z0-9]+)*$), loading anyway", name);
        }
        if (!name.equals(directoryName)) {
            LOGGER.warn("Skill name '{}' does not match directory name '{}', loading with skill name", name, directoryName);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseMetadata(Object raw) {
        if (raw == null) return Collections.emptyMap();
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return Collections.emptyMap();
    }

    private List<String> parseAllowedTools(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return Collections.emptyList();
            return List.of(trimmed.split("\\s+"));
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return Collections.emptyList();
    }
}
