package ai.core.cli.memory;

import ai.core.cli.DebugLog;
import ai.core.memory.MemoryProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * @author xander
 */
public class LocalFileMemoryProvider implements MemoryProvider {

    private static final long GLOBAL_MAX_BYTES = 5 * 1024L;
    private static final long PROJECT_MAX_BYTES = 10 * 1024L;

    private final Path globalPath;
    private final Path projectPath;

    public LocalFileMemoryProvider(Path workspace) {
        this.globalPath = Path.of(System.getProperty("user.home"), ".core-ai", "MEMORY.md");
        this.projectPath = workspace.resolve(".core-ai/MEMORY.md");
    }

    @Override
    public String load() {
        var sb = new StringBuilder(128);
        String global = readFileQuietly(globalPath);
        String project = readFileQuietly(projectPath);

        if (!global.isBlank()) {
            sb.append("--- Global Memory ---\n").append(global.strip()).append('\n');
        }
        if (!project.isBlank()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("--- Project Memory ---\n").append(project.strip()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public void save(String content) {
        writeEntry(projectPath, PROJECT_MAX_BYTES, "project", content);
    }

    @Override
    public int remove(String keyword) {
        int total = 0;
        total += removeFromFile(globalPath, keyword);
        total += removeFromFile(projectPath, keyword);
        return total;
    }

    // --- CLI-specific methods ---

    public void saveToScope(String scope, String content) {
        Path path = pathForScope(scope);
        long maxBytes = "global".equals(scope) ? GLOBAL_MAX_BYTES : PROJECT_MAX_BYTES;
        writeEntry(path, maxBytes, scope, content);
    }

    public long sizeInBytes(String scope) {
        Path path = pathForScope(scope);
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private void writeEntry(Path path, long maxBytes, String scope, String content) {
        long currentSize = fileSize(path);
        if (currentSize + content.length() + 3 > maxBytes) {
            throw new IllegalStateException("Memory capacity exceeded for scope '" + scope
                    + "' (limit: " + maxBytes / 1024 + "KB)");
        }
        try {
            Files.createDirectories(path.getParent());
            String line = "- " + content + "\n";
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write memory: " + e.getMessage(), e);
        }
    }

    private int removeFromFile(Path path, String keyword) {
        if (!Files.exists(path)) return 0;
        try {
            List<String> lines = Files.readAllLines(path);
            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
            List<String> remaining = lines.stream()
                    .filter(line -> !line.toLowerCase(Locale.ROOT).contains(lowerKeyword))
                    .toList();
            int removed = lines.size() - remaining.size();
            if (removed > 0) {
                Files.writeString(path, String.join("\n", remaining) + (remaining.isEmpty() ? "" : "\n"));
            }
            return removed;
        } catch (IOException e) {
            DebugLog.log("Failed to remove memory entries: " + e.getMessage());
            return 0;
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private Path pathForScope(String scope) {
        return "global".equals(scope) ? globalPath : projectPath;
    }

    private String readFileQuietly(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            DebugLog.log("Failed to read memory file " + path + ": " + e.getMessage());
            return "";
        }
    }
}
