package ai.core.cli.appserver;

import ai.core.cli.utils.PathUtils;
import ai.core.session.SessionFileNames;
import ai.core.session.SessionLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/**
 * Startup cleanup: stale atomic-write temp files in every workspace, and (engine only) attachment
 * staging directories that no workspace or live session owns. Best-effort per entry.
 *
 * @author stephen
 */
public final class Housekeeping {
    private static final Logger LOGGER = LoggerFactory.getLogger(Housekeeping.class);
    private static final long TEMP_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final long STAGING_AGE_MILLIS = TimeUnit.DAYS.toMillis(7);

    public static void run(Path sessionsDir) {
        cleanupTempFiles(sessionsDir);
    }

    public static void runEngine(Path sessionsDir) {
        runEngine(sessionsDir, stagingRoot(), PathUtils.SESSIONS_BASE_DIR);
    }

    static void runEngine(Path sessionsDir, Path stagingRoot) {
        runEngine(sessionsDir, stagingRoot, PathUtils.SESSIONS_BASE_DIR);
    }

    static void runEngine(Path sessionsDir, Path stagingRoot, Path sessionsBase) {
        cleanupTempFiles(sessionsDir);
        cleanupStaging(stagingRoot, sessionsBase);
    }

    private static void cleanupTempFiles(Path sessionsDir) {
        if (!Files.isDirectory(sessionsDir)) return;
        var cutoff = System.currentTimeMillis() - TEMP_AGE_MILLIS;
        try (var entries = Files.list(sessionsDir)) {
            entries.filter(Files::isRegularFile).forEach(file -> deleteStaleTempFile(file, cutoff));
        } catch (IOException e) {
            LOGGER.debug("failed to scan session temp files: {}", sessionsDir, e);
        }
    }

    private static void deleteStaleTempFile(Path file, long cutoff) {
        var fileName = file.getFileName();
        if (fileName == null) return;
        var name = fileName.toString();
        if (!name.startsWith(".") || !name.endsWith(".tmp")) return;
        try {
            if (Files.getLastModifiedTime(file).toMillis() >= cutoff) return;
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.debug("failed to remove stale temp file: {}", file, e);
        }
    }

    private static void cleanupStaging(Path stagingRoot, Path sessionsBase) {
        if (!Files.isDirectory(stagingRoot)) return;
        var cutoff = System.currentTimeMillis() - STAGING_AGE_MILLIS;
        try (var entries = Files.list(stagingRoot)) {
            entries.filter(Files::isDirectory).forEach(dir -> cleanupStagingDir(dir, cutoff, sessionsBase));
        } catch (IOException e) {
            LOGGER.debug("failed to scan staging directories: {}", stagingRoot, e);
        }
    }

    private static void cleanupStagingDir(Path dir, long cutoff, Path sessionsBase) {
        var fileName = dir.getFileName();
        if (fileName == null) return;
        var id = fileName.toString();
        try {
            SessionFileNames.check(id);
            if (Files.getLastModifiedTime(dir).toMillis() >= cutoff) return;
            if (sessionExistsAnywhere(sessionsBase, id)) return;
            deleteDir(dir);
        } catch (IllegalArgumentException e) {
            LOGGER.debug("ignoring staging directory with unsafe name: {}", dir);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("failed to remove stale staging directory: {}", dir, e);
        }
    }

    static void deleteDir(Path dir) {
        try (var paths = Files.walk(dir)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                deletePath(path);
            }
        } catch (IOException e) {
            LOGGER.debug("failed to delete staging directory: {}", dir, e);
        }
    }

    private static void deletePath(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("failed to delete staging path: {}", path, e);
        }
    }

    private static boolean sessionExistsAnywhere(Path sessionsBase, String id) {
        try (var workspaces = Files.list(sessionsBase)) {
            return workspaces.filter(Files::isDirectory).anyMatch(dir ->
                    Files.exists(dir.resolve(id + ".data")) || SessionLock.isHeld(dir, id));
        } catch (IOException e) {
            return true; // do not delete when the index cannot be checked
        }
    }

    private static Path stagingRoot() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "app-server", "staging");
    }

    private Housekeeping() {
    }
}
