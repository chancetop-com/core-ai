package ai.core.cli.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides access to builtin resources from classpath.
 * Scripts are extracted to a temp directory on first access.
 */
public class BuiltinResourceProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuiltinResourceProvider.class);
    private static final String INTERNAL_PREFIX = ".internal/";

    private static final AtomicReference<Path> TEMP_DIR = new AtomicReference<>();

    /**
     * Get the path to a builtin script. Extracts from classpath if needed.
     */
    public static Path getScriptPath(String classpathResource) {
        if (!classpathResource.startsWith(INTERNAL_PREFIX)) {
            // Not a builtin resource, use as-is
            return Path.of(classpathResource);
        }

        try {
            Path dir = getTempDir();
            Path targetPath = dir.resolve(classpathResource);

            if (Files.exists(targetPath)) {
                return targetPath;
            }

            // Extract from classpath
            extractResource(classpathResource, targetPath);
            return targetPath;
        } catch (IOException e) {
            LOGGER.warn("Failed to extract builtin resource {}: {}", classpathResource, e.getMessage());
            return Path.of(classpathResource);
        }
    }

    /**
     * Read builtin hooks.json content from classpath.
     */
    public static String loadBuiltinHooksJson() {
        try (InputStream is = BuiltinResourceProvider.class.getResourceAsStream("/.internal/hooks.json")) {
            if (is == null) {
                LOGGER.debug("No builtin hooks.json found in classpath");
                return null;
            }
            return readStream(is);
        } catch (IOException e) {
            LOGGER.warn("Failed to load builtin hooks.json: {}", e.getMessage());
            return null;
        }
    }

    private static String readStream(InputStream is) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8);
             StringWriter writer = new StringWriter()) {
            reader.transferTo(writer);
            return writer.toString();
        }
    }

    private static Path getTempDir() throws IOException {
        Path existing = TEMP_DIR.get();
        if (existing != null) {
            return existing;
        }

        Path temp = Files.createTempDirectory("core-ai-internal");
        TEMP_DIR.set(temp);
        LOGGER.debug("Created temp directory for builtin resources: {}", temp);
        return temp;
    }

    private static void extractResource(String classpathResource, Path targetPath) throws IOException {
        String resourcePath = "/" + classpathResource;

        try (InputStream is = BuiltinResourceProvider.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.warn("Builtin resource not found in classpath: {}", resourcePath);
                return;
            }

            Files.createDirectories(targetPath.getParent());
            Files.copy(is, targetPath);

            // Make shell scripts executable
            if (classpathResource.endsWith(".sh")) {
                targetPath.toFile().setExecutable(true, false);
            }

            LOGGER.debug("Extracted builtin resource: {} -> {}", resourcePath, targetPath);
        }
    }
}
