package ai.core.cli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MdMemoryProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(MdMemoryProvider.class);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^(\\w+):\\s*(.+)$", Pattern.MULTILINE);
    private static final int MAX_LOAD_BYTES = 20 * 1024;

    private final Path memoryDir;
    private final Path indexPath;

    public MdMemoryProvider(Path workspace) {
        this.memoryDir = workspace.resolve(".core-ai/memory");
        this.indexPath = workspace.resolve(".core-ai/MEMORY.md");
    }

    public String load() {
        var sb = new StringBuilder(1024);
        String index = readFileQuietly(indexPath);
        if (!index.isBlank()) {
            sb.append("--- Memory Index ---\n").append(index.strip()).append('\n');
        }
        if (Files.isDirectory(memoryDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir, "*.md")) {
                for (Path file : stream) {
                    String content = readFileQuietly(file);
                    if (content.isBlank()) continue;
                    if (sb.length() + content.length() > MAX_LOAD_BYTES) {
                        sb.append("\n(Memory truncated, use md_memory_tool to read remaining files)\n");
                        break;
                    }
                    sb.append("\n--- ").append(file.getFileName()).append(" ---\n")
                      .append(content.strip()).append('\n');
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load memory files: {}", e.getMessage());
            }
        }
        return sb.toString();
    }

    public Path getMemoryDir() {
        return memoryDir;
    }

    public List<MemoryEntry> listMemories() {
        List<MemoryEntry> entries = new ArrayList<>();
        // Always include MEMORY.md index file first
        if (Files.isRegularFile(indexPath)) {
            var indexEntry = new MemoryEntry("MEMORY.md", "Memory Index", "Main memory index file", "index", "",
                    indexPath.toAbsolutePath().toString());
            entries.add(indexEntry);
        }
        if (!Files.isDirectory(memoryDir)) {
            return entries;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir, "*.md")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                if ("MEMORY.md".equals(filename)) continue;
                MemoryEntry entry = parseMemoryFile(file);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to list memory files: {}", e.getMessage());
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

    public String readMemoryFile(String relativePath, int from, int lines) {
        String resolved = relativePath.endsWith(".md") ? relativePath : relativePath + ".md";
        Path filePath = memoryDir.resolve(resolved);
        if (!Files.exists(filePath)) {
            return null;
        }
        String content = readFileQuietly(filePath);
        if (from <= 0 && lines <= 0) {
            return content;
        }
        List<String> allLines = content.lines().toList();
        int start = Math.max(0, from - 1);
        int end = lines > 0 ? Math.min(allLines.size(), start + lines) : allLines.size();
        if (start >= allLines.size()) {
            return "";
        }
        return String.join("\n", allLines.subList(start, end));
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
