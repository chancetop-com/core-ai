package ai.core.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Crash-safe file replacement: write a sibling temp file, force it, then rename it over the target.
 * A failed write never touches the previous target; a reader sees either the old or the new file.
 *
 * @author stephen
 */
public final class AtomicFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger(AtomicFiles.class);
    private static final int MOVE_ATTEMPTS = 3;
    private static final long MOVE_RETRY_MILLIS = 50L;

    @FunctionalInterface
    interface Writer {
        void write(Path path, byte[] content) throws IOException;
    }

    // Test hook; thread-local so parallel test classes cannot replace each other's writer.
    static final ThreadLocal<Writer> WRITER = new ThreadLocal<>();

    public static void writeString(Path target, String content) {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    public static void write(Path target, byte[] content) {
        var absolute = target.toAbsolutePath();
        var fileName = absolute.getFileName();
        if (fileName == null) throw new IllegalArgumentException("target must name a file: " + target);
        var parent = absolute.getParent();
        var directory = parent == null ? Path.of(".") : parent;
        Path tmp = null;
        try {
            Files.createDirectories(directory);
            tmp = Files.createTempFile(directory, "." + fileName + "." + ProcessHandle.current().pid() + ".", ".tmp");
            var writer = WRITER.get();
            if (writer != null) {
                writer.write(tmp, content);
            } else {
                Files.write(tmp, content);
            }
            if (fsyncEnabled()) {
                force(tmp);
            }
            move(tmp, absolute);
            tmp = null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write atomically: " + target, e);
        } finally {
            deleteQuietly(tmp);
        }
    }

    private static void deleteQuietly(Path tmp) {
        if (tmp == null) return;
        try {
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            LOGGER.debug("failed to delete temporary file: {}", tmp, e);
        }
    }

    private static boolean fsyncEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("core.ai.fsync"));
    }

    private static void force(Path tmp) throws IOException {
        try (var channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void move(Path tmp, Path target) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                moveOnce(tmp, target);
                return;
            } catch (IOException e) {
                if (attempt >= MOVE_ATTEMPTS - 1) throw e;
                sleepBeforeRetry();
            }
        }
    }

    private static void moveOnce(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepBeforeRetry() throws IOException {
        try {
            Thread.sleep(MOVE_RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while moving the atomic file", e);
        }
    }

    private AtomicFiles() {
    }
}
