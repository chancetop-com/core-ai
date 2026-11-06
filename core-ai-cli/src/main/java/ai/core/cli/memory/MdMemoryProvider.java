package ai.core.cli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    private static final int MAX_INDEX_CHARS = 4 * 1024;
    private static final int MAX_USER_CHARS = 2 * 1024;
    private static final int MAX_EPISODE_CHARS = 4 * 1024;
    private static final int MAX_RECENT_PAGES = 10;
    private static final int PAGE_DESCRIPTION_MAX_CHARS = 120;
    private static final Pattern EPISODE_FILE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}\\.md$");
    private static final int MAX_RECENT_EPISODES = 2;

    private static boolean isEpisodeFile(Path p) {
        if (!Files.isRegularFile(p)) return false;
        Path fn = p.getFileName();
        return fn != null && EPISODE_FILE_PATTERN.matcher(fn.toString()).matches();
    }

    private final Path memoryDir;
    private final Path indexPath;
    private final Path episodesDir;
    private final GlobalUserMemory globalMemory;

    public MdMemoryProvider(Path workspace) {
        this(workspace, new GlobalUserMemory());
    }

    MdMemoryProvider(Path workspace, GlobalUserMemory globalMemory) {
        this.memoryDir = workspace.resolve(".core-ai/knowledge");
        this.indexPath = workspace.resolve(".core-ai/knowledge/MEMORY.md");
        this.episodesDir = workspace.resolve(".core-ai/episodes");
        this.globalMemory = globalMemory;
    }

    /**
     * Load initial memory context for injection into the system prompt.
     * Includes: global user memory (all workspaces), MEMORY.md index, user-type knowledge pages,
     * a newest-first list of recent knowledge pages, and the latest 2 episodes.
     */
    public String load() {
        var sb = new StringBuilder(1024);

        String global = globalMemory.load();
        if (!global.isBlank()) {
            sb.append(GlobalUserMemory.HEADER).append('\n').append(global).append('\n');
        }

        String index = readFileQuietly(indexPath);
        if (!index.isBlank()) {
            sb.append("--- Memory Index ---\n").append(compactIndex(index.strip(), MAX_INDEX_CHARS)).append('\n');
        }

        loadTypeDir(sb, memoryDir.resolve("user"), "user", MAX_USER_CHARS);
        loadRecentPages(sb);
        loadRecentEpisodes(sb, MAX_EPISODE_CHARS);

        return sb.toString();
    }

    public GlobalUserMemory getGlobalMemory() {
        return globalMemory;
    }

    public Path getMemoryDir() {
        return memoryDir;
    }

    public List<MemoryEntry> listMemories() {
        List<MemoryEntry> entries = new ArrayList<>();
        String globalBody = globalMemory.load();
        if (!globalBody.isBlank()) {
            entries.add(new MemoryEntry("global/" + GlobalUserMemory.FILE_NAME, "Global User Memory",
                    "Cross-project user preferences", "global", globalBody,
                    globalMemory.path().toAbsolutePath().toString()));
        }
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
                LOGGER.debug("Type directory does not exist, skipping: {}", e.getMessage());
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
        Path fn = file.getFileName();
        return new MemoryEntry(fn != null ? fn.toString() : file.toString(), name, description, type, body,
                file.toAbsolutePath().toString());
    }

    private void loadTypeDir(StringBuilder sb, Path typeDir, String type, int budget) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(typeDir, "*.md")) {
            for (Path file : stream) files.add(file);
        } catch (IOException e) {
            LOGGER.warn("Failed to load {} directory: {}", type, e.getMessage());
            return;
        }
        files.sort(Comparator.comparing(this::lastModified).reversed());

        int remaining = budget;
        for (Path file : files) {
            String content = readFileQuietly(file);
            if (content.isBlank()) continue;
            String block = "\n--- " + type + '/' + file.getFileName() + " ---\n" + content.strip() + '\n';
            if (block.length() > remaining) {
                sb.append("\n(Memory truncated, use read_file to read remaining files)\n");
                return;
            }
            sb.append(block);
            remaining -= block.length();
        }
    }

    /** Shows the most recently updated pages even when MEMORY.md is stale or truncated. */
    private void loadRecentPages(StringBuilder sb) {
        List<Path> files = new ArrayList<>();
        for (var type : List.of("project", "user", "feedback", "reference")) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(memoryDir.resolve(type), "*.md")) {
                for (Path file : stream) files.add(file);
            } catch (IOException e) {
                LOGGER.debug("Type directory does not exist, skipping: {}", e.getMessage());
            }
        }
        if (files.isEmpty()) return;
        files.sort(Comparator.comparing(this::lastModified).reversed());

        sb.append("\n--- Recent Knowledge Pages (newest first) ---\n");
        int shown = 0;
        for (Path file : files) {
            if (shown >= MAX_RECENT_PAGES) break;
            MemoryEntry entry = parseMemoryFile(file);
            if (entry == null) continue;
            String description = entry.description().isBlank() ? firstLine(entry.body()) : entry.description();
            sb.append("- ").append(typeOf(file)).append('/').append(file.getFileName());
            if (!description.isBlank()) {
                sb.append(" — ").append(truncateLine(description, PAGE_DESCRIPTION_MAX_CHARS));
            }
            sb.append('\n');
            shown++;
        }
    }

    private void loadRecentEpisodes(StringBuilder sb, int budget) {
        List<Path> episodeFiles;
        try (Stream<Path> stream = Files.list(episodesDir)) {
            episodeFiles = stream
                    .filter(MdMemoryProvider::isEpisodeFile)
                    .sorted(Comparator.reverseOrder())
                    .limit(MAX_RECENT_EPISODES)
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("Failed to list episodes: {}", e.getMessage());
            return;
        }

        if (episodeFiles.isEmpty()) return;

        sb.append("\n--- Recent Episodes ---\n");
        int remaining = budget;
        for (Path file : episodeFiles) {
            String content = readFileQuietly(file);
            if (content.isBlank()) continue;
            String header = "--- episodes/" + file.getFileName() + " ---\n";
            if (header.length() + content.strip().length() > remaining) {
                int keep = remaining - header.length() - 64;
                if (keep > 0) {
                    String tail = content.strip();
                    tail = tail.substring(Math.max(0, tail.length() - keep));
                    sb.append(header).append("(latest part)\n").append(tail).append('\n');
                }
                sb.append("(More episodes available, use read_file)\n");
                return;
            }
            sb.append(header).append(content.strip()).append('\n');
            remaining -= header.length() + content.strip().length() + 1;
        }
    }

    private FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private String typeOf(Path file) {
        Path parent = file.getParent();
        if (parent == null) return "";
        Path name = parent.getFileName();
        return name == null ? "" : name.toString();
    }

    private String firstLine(String body) {
        for (String line : body.split("\n")) {
            String stripped = line.replaceFirst("^#+\\s*", "").strip();
            if (!stripped.isEmpty()) return stripped;
        }
        return "";
    }

    private String truncateLine(String text, int maxChars) {
        String collapsed = text.replaceAll("\\s+", " ").strip();
        return collapsed.length() <= maxChars ? collapsed : collapsed.substring(0, maxChars) + "...";
    }

    /**
     * Keeps every type section visible when the index is larger than the prompt budget: each section
     * gets an equal row allowance and the newest rows (the index is written newest-first) survive.
     */
    private String compactIndex(String content, int maxChars) {
        if (content.length() <= maxChars) return content;

        String title = "# Knowledge Index";
        var sectionNames = new ArrayList<String>();
        var headers = new ArrayList<List<String>>();
        var rows = new ArrayList<List<String>>();
        for (var line : content.split("\n", -1)) {
            if (line.startsWith("# ") && sectionNames.isEmpty()) {
                title = line;
            } else if (line.startsWith("## ")) {
                sectionNames.add(line);
                headers.add(new ArrayList<>());
                rows.add(new ArrayList<>());
            } else if (!sectionNames.isEmpty() && line.startsWith("| File ")) {
                headers.get(headers.size() - 1).add(line);
            } else if (!sectionNames.isEmpty() && line.startsWith("|---")) {
                headers.get(headers.size() - 1).add(line);
            } else if (!sectionNames.isEmpty() && line.startsWith("|")) {
                rows.get(rows.size() - 1).add(line);
            }
        }
        if (sectionNames.isEmpty()) return content.substring(0, maxChars);

        int overhead = title.length() + 2;
        for (int i = 0; i < sectionNames.size(); i++) {
            overhead += sectionNames.get(i).length() + 4;
            for (var header : headers.get(i)) overhead += header.length() + 1;
        }
        int perSection = Math.max(80, Math.max(0, maxChars - overhead) / sectionNames.size());

        var sb = new StringBuilder(maxChars + 256);
        sb.append(title).append("\n\n");
        for (int i = 0; i < sectionNames.size(); i++) {
            sb.append(sectionNames.get(i)).append("\n\n");
            for (var header : headers.get(i)) sb.append(header).append('\n');
            int used = 0;
            int shown = 0;
            var section = rows.get(i);
            for (var row : section) {
                if (used + row.length() + 1 > perSection) break;
                sb.append(row).append('\n');
                used += row.length() + 1;
                shown++;
            }
            if (shown < section.size()) {
                sb.append("| ... ").append(section.size() - shown).append(" more rows — read MEMORY.md |\n");
            }
            sb.append('\n');
        }
        return sb.toString();
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
