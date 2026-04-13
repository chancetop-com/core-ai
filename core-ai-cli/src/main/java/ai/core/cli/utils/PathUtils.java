package ai.core.cli.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Utility class for managing core-ai paths and directories.
 */
public final class PathUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(PathUtils.class);

    private static final Path HOME_DIR = Path.of(System.getProperty("user.home"));
    private static final Path CORE_AI_DIR = HOME_DIR.resolve(".core-ai");

    public static final Path DEFAULT_CONFIG = CORE_AI_DIR.resolve("agent.properties");
    public static final Path SESSIONS_BASE_DIR = CORE_AI_DIR.resolve("sessions");
    public static final Path PLUGINS_DIR = CORE_AI_DIR.resolve("plugins");
    public static final Path LIB_DIR = CORE_AI_DIR.resolve("lib");

    /**
     * Get sessions directory for a given workspace.
     */
    public static String sessionsDir(Path workspace) {
        return SESSIONS_BASE_DIR.resolve(workspace.getFileName().toString()).toString();
    }

    /**
     * Get the JAR path where this application is running from.
     * Used to extract bundled resources like skills.
     */
    public static Path getJarPath() {
        String jarPath = System.getProperty("core.ai.jar.path");
        if (jarPath != null && !jarPath.isBlank()) {
            return Path.of(jarPath);
        }

        try {
            var protectionDomain = PathUtils.class.getProtectionDomain();
            var codeSource = protectionDomain.getCodeSource();
            if (codeSource != null) {
                var url = codeSource.getLocation();
                if (url != null && "file".equals(url.getProtocol())) {
                    return Path.of(url.toURI());
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get JAR path from classloader: {}", e.getMessage());
        }

        Path homeJar = LIB_DIR.resolve("core-ai-cli.jar");
        if (Files.exists(homeJar)) {
            return homeJar;
        }

        LOGGER.warn("Could not determine JAR path, skills initialization may fail");
        return Path.of("core-ai-cli.jar");
    }

    private PathUtils() { }
}
