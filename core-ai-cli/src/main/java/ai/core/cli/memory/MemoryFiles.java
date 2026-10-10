package ai.core.cli.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Filesystem helpers for the memory store: recursive deletion (knowledge reset) and lock-file
 * discovery (startup daily-log processing).
 *
 * @author stephen
 */
final class MemoryFiles {
    static final String LOCK_SUFFIX = ".lock";

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryFiles.class);

    static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(MemoryFiles::tryDelete);
        }
    }

    static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            LOGGER.debug("Failed to delete {}: {}", path, ignored.getMessage());
        }
    }

    static boolean isLockFile(Path path) {
        if (!Files.isRegularFile(path)) return false;
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().endsWith(LOCK_SUFFIX);
    }

    private MemoryFiles() {
    }
}
