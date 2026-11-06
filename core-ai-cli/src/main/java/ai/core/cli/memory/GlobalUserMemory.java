package ai.core.cli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Global user memory: a single markdown file at {@code ~/.core-ai/MEMORY.md} holding durable
 * preferences that apply to every workspace (language, workflow habits, tooling, recurring
 * corrections). Unlike the workspace-scoped knowledge wiki, it is injected into the system
 * prompt regardless of which project the CLI runs in.
 *
 * @author stephen
 */
public final class GlobalUserMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalUserMemory.class);

    static final String HEADER = "--- Global User Memory (all workspaces) ---";
    static final String FILE_NAME = "MEMORY.md";
    static final String ARCHIVE_FILE_NAME = "MEMORY.archive.md";
    static final int MAX_CHARS = 8 * 1024;
    private static final String TRIMMED_MARKER =
            "(older entries trimmed — showing the most recent ones)";

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".core-ai", FILE_NAME);
    }

    private static int keepFrom(String content, int maxChars) {
        if (content.length() <= maxChars) return 0;
        int start = content.length() - maxChars;
        int lineStart = content.indexOf('\n', start);
        return lineStart >= 0 ? lineStart + 1 : start;
    }

    private final Path filePath;

    public GlobalUserMemory() {
        this(defaultPath());
    }

    GlobalUserMemory(Path filePath) {
        this.filePath = filePath;
    }

    public Path path() {
        return filePath;
    }

    /**
     * @return the stored preferences, or "" when the file is absent or blank; the most recent
     * entries are kept (with a marker) when the file exceeds {@link #MAX_CHARS}
     */
    public String load() {
        try {
            if (!Files.isRegularFile(filePath)) return "";
            String content = Files.readString(filePath).strip();
            if (content.isEmpty()) return "";
            int keepFrom = keepFrom(content, MAX_CHARS);
            return keepFrom == 0 ? content : TRIMMED_MARKER + "\n" + content.substring(keepFrom);
        } catch (IOException e) {
            LOGGER.warn("Failed to read global user memory {}: {}", filePath, e.getMessage());
            return "";
        }
    }

    /**
     * Appends one preference as a markdown bullet. The store is bounded: when the new line does not
     * fit, the oldest complete lines move to the archive file so that the most recent preferences
     * always survive and nothing is lost.
     *
     * @return {@code true} when the file did not exist before this call
     */
    public boolean append(String content) {
        String line = "- " + content.strip() + "\n";
        try {
            Path parent = filePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean created = !Files.isRegularFile(filePath);
            String existing = created ? "" : Files.readString(filePath);
            if (!existing.isEmpty() && !existing.endsWith("\n")) existing += "\n";

            String updated = existing + line;
            int keepFrom = keepFrom(updated, MAX_CHARS);
            if (keepFrom > 0) {
                appendArchive(updated.substring(0, keepFrom));
                updated = updated.substring(keepFrom);
            }
            Files.writeString(filePath, updated,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return created;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write global user memory: " + e.getMessage(), e);
        }
    }

    private Path archivePath() {
        Path parent = filePath.getParent();
        return parent == null ? Path.of(ARCHIVE_FILE_NAME) : parent.resolve(ARCHIVE_FILE_NAME);
    }

    private void appendArchive(String entries) {
        try {
            Path archive = archivePath();
            Path parent = archive.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(archive, entries,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("Failed to archive trimmed global memory {}: {}", archivePath(), e.getMessage());
        }
    }
}
