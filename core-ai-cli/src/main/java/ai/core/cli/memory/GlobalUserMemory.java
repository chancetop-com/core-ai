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
    static final int MAX_BYTES = 8 * 1024;

    public static Path defaultPath() {
        return Path.of(System.getProperty("user.home"), ".core-ai", FILE_NAME);
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
     * @return the stored preferences, or "" when the file is absent or blank; truncated with a
     * marker when it exceeds {@link #MAX_BYTES}
     */
    public String load() {
        try {
            if (!Files.isRegularFile(filePath)) return "";
            String content = Files.readString(filePath).strip();
            if (content.isEmpty()) return "";
            if (content.length() > MAX_BYTES) {
                return content.substring(0, MAX_BYTES)
                        + "\n(truncated — edit " + filePath + " to trim)";
            }
            return content;
        } catch (IOException e) {
            LOGGER.warn("Failed to read global user memory {}: {}", filePath, e.getMessage());
            return "";
        }
    }

    /**
     * Append one preference as a markdown bullet; fails when the store would exceed its cap.
     *
     * @return {@code true} when the file did not exist before this call
     */
    public boolean append(String content) {
        String line = "- " + content.strip() + "\n";
        try {
            Path parent = filePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            boolean created = !Files.isRegularFile(filePath);
            long size = created ? 0 : Files.size(filePath);
            if (size + line.length() > MAX_BYTES) {
                throw new IllegalStateException("Global user memory is full (limit " + MAX_BYTES / 1024
                        + "KB); edit " + filePath + " to remove stale entries");
            }
            Files.writeString(filePath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return created;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write global user memory: " + e.getMessage(), e);
        }
    }
}
