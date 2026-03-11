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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @author xander
 */
public class SkillLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_FILE_NAME = "SKILL.md";
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL);
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int MAX_SKILL_NAME_LENGTH = 64;

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
            var skills = loadFromSource(source.getPath());
            for (var skill : skills) {
                skillMap.put(skill.getName(), skill);
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
                if (!Files.exists(skillFile)) continue;
                if (!isWithinDirectory(skillFile, realDir)) {
                    LOGGER.warn("Skill file path escapes source directory: {}", skillFile);
                    continue;
                }
                var skill = loadSkillFile(skillFile, entry.getFileName().toString());
                if (skill != null) {
                    skills.add(skill);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan skill source directory: {}", sourcePath, e);
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

    private boolean isWithinDirectory(Path file, Path directory) {
        try {
            return file.toRealPath().startsWith(directory);
        } catch (IOException e) {
            return false;
        }
    }

    private SkillMetadata loadSkillFile(Path skillFile, String directoryName) {
        try {
            byte[] bytes = Files.readAllBytes(skillFile);
            if (bytes.length > maxSkillFileSize) {
                LOGGER.warn("Skill file exceeds max size ({}): {}", maxSkillFileSize, skillFile);
                return null;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            Path skillDir = skillFile.getParent();
            List<String> scannedFiles = scanResourceFiles(skillDir);
            SkillMetadata base = parseSkillMd(content, skillFile.toAbsolutePath().toString(), directoryName);
            if (base == null) return null;
            List<SkillMetadata.ReferenceEntry> mergedRefs = mergeReferences(base.getReferences(), scannedFiles);
            return SkillMetadata.builder(base.getName(), base.getDescription(), base.getPath())
                    .type(base.getType())
                    .skillDir(skillDir.toAbsolutePath().toString())
                    .license(base.getLicense())
                    .compatibility(base.getCompatibility())
                    .metadata(base.getMetadata())
                    .allowedTools(base.getAllowedTools())
                    .triggers(base.getTriggers())
                    .references(mergedRefs)
                    .examples(base.getExamples())
                    .outputFormat(base.getOutputFormat())
                    .build();
        } catch (IOException e) {
            LOGGER.warn("Failed to read skill file: {}", skillFile, e);
            return null;
        }
    }

    List<String> scanResourceFiles(Path skillDir) {
        String[] subDirs = {"scripts", "references"};
        List<String> result = new ArrayList<>();
        for (String sub : subDirs) {
            Path subDir = skillDir.resolve(sub);
            if (!Files.isDirectory(subDir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(subDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry)) {
                        result.add(sub + "/" + entry.getFileName().toString());
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to scan resources in {}: {}", subDir, e.getMessage());
            }
        }
        Collections.sort(result);
        return result;
    }

    List<SkillMetadata.ReferenceEntry> mergeReferences(List<SkillMetadata.ReferenceEntry> declared, List<String> scannedFiles) {
        if ((declared == null || declared.isEmpty()) && (scannedFiles == null || scannedFiles.isEmpty())) {
            return Collections.emptyList();
        }
        Set<String> declaredFiles = new LinkedHashSet<>();
        List<SkillMetadata.ReferenceEntry> merged = new ArrayList<>();
        if (declared != null) {
            for (var ref : declared) {
                declaredFiles.add(ref.file());
                merged.add(ref);
            }
        }
        if (scannedFiles != null) {
            for (String file : scannedFiles) {
                if (!declaredFiles.contains(file)) {
                    merged.add(new SkillMetadata.ReferenceEntry(file, ""));
                }
            }
        }
        return merged;
    }

    SkillMetadata parseSkillMd(String content, String filePath, String directoryName) {
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            LOGGER.warn("No YAML frontmatter in {}", filePath);
            return null;
        }
        try {
            var options = new LoaderOptions();
            options.setCodePointLimit(maxSkillFileSize);
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

            String type = (String) data.get("type");
            String license = (String) data.get("license");
            String compatibility = (String) data.get("compatibility");
            Map<String, String> metadata = parseMetadata(data.get("metadata"));
            List<String> allowedTools = parseAllowedTools(data.get("allowed-tools"));
            List<String> triggers = parseStringList(data.get("triggers"));
            List<SkillMetadata.ReferenceEntry> references = parseReferences(data.get("references"));
            List<String> examples = parseStringList(data.get("examples"));
            String outputFormat = (String) data.get("output-format");

            return SkillMetadata.builder(name, description, filePath)
                    .type(type)
                    .license(license)
                    .compatibility(compatibility)
                    .metadata(metadata)
                    .allowedTools(allowedTools)
                    .triggers(triggers)
                    .references(references)
                    .examples(examples)
                    .outputFormat(outputFormat)
                    .build();
        } catch (Exception e) {
            LOGGER.warn("Failed to parse YAML frontmatter in {}", filePath, e);
            return null;
        }
    }

    boolean validateSkillName(String name, String directoryName) {
        if (name == null || name.length() > MAX_SKILL_NAME_LENGTH) return false;
        if (!SKILL_NAME_PATTERN.matcher(name).matches()) return false;
        return name.equals(directoryName);
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

    private List<String> parseStringList(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (raw instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return Collections.emptyList();
            return List.of(trimmed);
        }
        return Collections.emptyList();
    }

    private List<SkillMetadata.ReferenceEntry> parseReferences(Object raw) {
        if (raw == null) return Collections.emptyList();
        if (!(raw instanceof List<?> list)) return Collections.emptyList();
        List<SkillMetadata.ReferenceEntry> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String file = map.get("file") != null ? String.valueOf(map.get("file")) : null;
                if (file != null && !file.isBlank()) {
                    String desc = map.get("description") != null ? String.valueOf(map.get("description")) : "";
                    result.add(new SkillMetadata.ReferenceEntry(file, desc));
                }
            }
        }
        return result;
    }
}
