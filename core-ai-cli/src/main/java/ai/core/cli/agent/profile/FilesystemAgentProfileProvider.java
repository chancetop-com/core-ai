package ai.core.cli.agent.profile;

import ai.core.agent.profile.AgentProfile;
import ai.core.agent.profile.AgentProfileProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Scans a directory for agent definition markdown files (*.md)
 * and parses them into AgentProfile objects.
 *
 * @author lim chen
 */
public class FilesystemAgentProfileProvider implements AgentProfileProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(FilesystemAgentProfileProvider.class);
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", Pattern.DOTALL);

    private final String id;
    private final Path agentsDir;
    private final int priority;

    public FilesystemAgentProfileProvider(String id, Path agentsDir, int priority) {
        this.id = id;
        this.agentsDir = agentsDir;
        this.priority = priority;
    }

    @Override
    public List<AgentProfile> provide() {
        if (!Files.isDirectory(agentsDir)) {
            return List.of();
        }
        List<AgentProfile> profiles = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(agentsDir, "*.md")) {
            for (Path file : stream) {
                try {
                    AgentProfile profile = parseFile(file);
                    if (profile != null) {
                        profiles.add(profile);
                    }
                } catch (Exception e) {
                    LOGGER.warn("failed to load agent profile from {}", file, e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("failed to scan agents directory: {}", agentsDir, e);
        }
        return profiles;
    }

    @Override
    public int priority() {
        return priority;
    }

    private AgentProfile parseFile(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            LOGGER.warn("failed to read agent file: {}", file, e);
            return null;
        }

        String name = deriveName(file);
        var matcher = FRONTMATTER_PATTERN.matcher(content);
        if (matcher.find()) {
            return parseWithFrontmatter(name, matcher.group(1), content.substring(matcher.end()).trim(), file);
        }
        return parseWithoutFrontmatter(name, content, file);
    }

    private AgentProfile parseWithFrontmatter(String name, String yamlStr, String body, Path file) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(1024 * 1024);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Map<String, Object> data = yaml.load(yamlStr);
            if (data == null) {
                LOGGER.warn("empty YAML frontmatter in {}", file);
                return null;
            }

            String description = (String) data.get("description");
            if (description == null || description.isBlank()) {
                LOGGER.warn("missing description in {}", file);
                return null;
            }

            AgentProfile profile = new AgentProfile()
                    .name(name)
                    .description(description)
                    .systemPrompt(body.isEmpty() ? "" : body)
                    .path(file.toString())
                    .source("filesystem")
                    .priority(priority);

            if (data.containsKey("model")) {
                profile.model((String) data.get("model"));
            }
            if (data.containsKey("temperature")) {
                profile.temperature(((Number) data.get("temperature")).doubleValue());
            }
            if (data.containsKey("maxTurnNumber")) {
                profile.maxTurnNumber((Integer) data.get("maxTurnNumber"));
            }
            if (data.containsKey("tools") && data.get("tools") instanceof List<?> tools) {
                List<String> toolList = new ArrayList<>();
                for (Object t : tools) {
                    if (t instanceof String s) toolList.add(s);
                }
                profile.tools(toolList);
            }
            return profile;
        } catch (Exception e) {
            LOGGER.warn("failed to parse YAML frontmatter in {}", file, e);
            return null;
        }
    }

    private AgentProfile parseWithoutFrontmatter(String name, String content, Path file) {
        if (content.isBlank()) {
            LOGGER.warn("empty agent file: {}", file);
            return null;
        }
        // When no YAML frontmatter, use the filename as description and entire content as system prompt
        return new AgentProfile()
                .name(name)
                .description("Custom agent: " + name)
                .systemPrompt(content.trim())
                .path(file.toString())
                .source("filesystem")
                .priority(priority);
    }

    String deriveName(Path file) {
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }
}
