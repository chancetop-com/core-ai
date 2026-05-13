package ai.core.cli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class MdMemoryProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(MdMemoryProvider.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^(\\w+):\\s*(.+)$", Pattern.MULTILINE);
    private static final int MAX_LOAD_BYTES = 2 * 1024;
    private static final Pattern EPISODE_FILE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.md$");
    private static final int MAX_RECENT_EPISODES = 2;

    private final Path memoryDir;
    private final Path indexPath;
    private final Path episodesDir;

    public MdMemoryProvider(Path workspace) {
        this.memoryDir = workspace.resolve(".core-ai/knowledge");
        this.indexPath = workspace.resolve(".core-ai/knowledge/MEMORY.md");
        this.episodesDir = workspace.resolve(".core-ai/episodes");
    }

    /**
     * Load initial memory context for injection into the system prompt.
     * Includes: MEMORY.md index, user-type knowledge pages, and latest 2 episodes.
     */
    public String load() {
        var sb = new StringBuilder(1024);

        String index = readFileQuietly(indexPath);
        if (!index.isBlank()) {
            sb.append("--- Memory Index ---\n").append(index.strip()).append('\n');
        }

        loadTypeDir(sb, memoryDir.resolve("user"), "user");
        loadRecentEpisodes(sb);

        return sb.toString();
    }

    public Path getMemoryDir() {
        return memoryDir;
    }

    public List<MemoryEntry> listMemories() {
        List<MemoryEntry> entries = new ArrayList<>();
        // MEMORY.md is the index, at knowledge/ root — not in a type subdirectory
        if (Files.isRegularFile(indexPath)) {
            entries.add(new MemoryEntry("MEMORY.md", "Memory Index", "Main memory index file",
                    "index", "", indexPath.toAbsolutePath().toString()));
        }
        for (var type : List.of("project", "user", "feedback", "reference")) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir.resolve(type), "*.md")) {
                for (Path file : stream) {
                    MemoryEntry entry = parseMemoryFile(file);
                    if (entry != null) entries.add(entry);
                }
            } catch (IOException e) {
                // type directory doesn't exist — skip
            }
        }
        return entries;
    }

    public List<MemoryEntry> searchMemories(String query) {
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        List<MemoryEntry> results = new ArrayList<>();
        for (MemoryEntry entry : listMemories()) {
            if (entry.matches(lowerQuery)) {
                results.add(entry);
            }
        }
        return results;
    }

    private MemoryEntry parseMemoryFile(Path file) {
        String content = readFileQuietly(file);
        if (content.isBlank()) return null;

        String name = "";
        String description = "";
        String type = "";
        String body = content;

        Matcher frontmatterMatcher = FRONTMATTER_PATTERN.matcher(content);
        if (frontmatterMatcher.find()) {
            String frontmatter = frontmatterMatcher.group(1);
            body = content.substring(frontmatterMatcher.end()).strip();
            Matcher fieldMatcher = FIELD_PATTERN.matcher(frontmatter);
            while (fieldMatcher.find()) {
                switch (fieldMatcher.group(1)) {
                    case "name" -> name = fieldMatcher.group(2).strip();
                    case "description" -> description = fieldMatcher.group(2).strip();
                    case "type" -> type = fieldMatcher.group(2).strip();
                    default -> { }
                }
            }
        }
        return new MemoryEntry(file.getFileName().toString(), name, description, type, body,
                file.toAbsolutePath().toString());
    }

    private void loadTypeDir(StringBuilder sb, Path typeDir, String type) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(typeDir, "*.md")) {
            for (Path file : stream) {
                String content = readFileQuietly(file);
                if (content.isBlank()) continue;
                if (sb.length() + content.length() > MAX_LOAD_BYTES) {
                    sb.append("\n(Memory truncated, use read_file to read remaining files)\n");
                    return;
                }
                sb.append("\n--- ").append(type).append('/').append(file.getFileName()).append(" ---\n")
                  .append(content.strip()).append('\n');
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to load {} directory: {}", type, e.getMessage());
        }
    }

    private void loadRecentEpisodes(StringBuilder sb) {
        List<Path> episodeFiles;
        try (Stream<Path> stream = Files.list(episodesDir)) {
            episodeFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> EPISODE_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_RECENT_EPISODES)
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("Failed to list episodes: {}", e.getMessage());
            return;
        }

        if (episodeFiles.isEmpty()) return;

        sb.append("\n--- Recent Episodes ---\n");
        for (Path file : episodeFiles) {
            String content = readFileQuietly(file);
            if (content.isBlank()) continue;
            if (sb.length() + content.length() > MAX_LOAD_BYTES) {
                sb.append("(More episodes available, use read_file)\n");
                return;
            }
            sb.append("--- episodes/").append(file.getFileName()).append(" ---\n")
              .append(content.strip()).append('\n');
        }
    }

    private String readFileQuietly(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            LOGGER.warn("Failed to read file {}: {}", path, e.getMessage());
            return "";
        }
    }

    public record MemoryEntry(String fileName, String name, String description, String type, String body,
                               String absolutePath) {
        public boolean matches(String lowerQuery) {
            return name.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    || description.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    || type.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    || body.toLowerCase(Locale.ROOT).contains(lowerQuery);
        }

        public String toSummary() {
            var sb = new StringBuilder();
            sb.append("- ").append(fileName);
            if (!name.isBlank()) sb.append(" | ").append(name);
            if (!type.isBlank()) sb.append(" [").append(type).append(']');
            if (!description.isBlank()) sb.append(" - ").append(description);
            return sb.toString();
        }
    }
}
