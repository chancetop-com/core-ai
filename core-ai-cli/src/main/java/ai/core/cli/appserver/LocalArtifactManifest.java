package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Append-only registry of files the agent registered through {@code submit_artifacts}: one JSON object
 * per line under {@code {workspace}/.core-ai/artifacts/manifest.jsonl}. {@link LocalArtifacts} merges the
 * entries into the panel list and the desktop serves registered paths outside {@code .core-ai} through
 * the artifact protocol — path and format are a cross-process contract with the desktop (see its
 * {@code artifactPath.ts}).
 *
 * @author stephen
 */
final class LocalArtifactManifest {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalArtifactManifest.class);
    /** Serializes appends across live sessions in this engine process. */
    private static final Object APPEND_LOCK = new Object();

    static Path file(Path workspace) {
        return workspace.resolve(".core-ai").resolve("artifacts").resolve("manifest.jsonl");
    }

    /** Registered paths in registration order, duplicates collapsed; malformed lines are skipped. */
    static List<String> paths(Path workspace) {
        var file = file(workspace);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        try {
            for (var line : Files.readAllLines(file)) {
                var path = parsePath(line);
                if (path != null) {
                    paths.add(path);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("failed to read the artifact manifest {}: {}", file, e.getMessage());
        }
        return List.copyOf(paths);
    }

    static void append(Path workspace, String path) throws IOException {
        var file = file(workspace);
        var parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var line = JsonUtil.OBJECT_MAPPER.createObjectNode()
                .put("path", path)
                .put("registeredAt", Instant.now().toString());
        synchronized (APPEND_LOCK) {
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        }
    }

    private static String parsePath(String line) {
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(line);
            var path = node == null ? null : node.get("path");
            if (path != null && path.isTextual() && !path.asText().isBlank()) {
                return path.asText();
            }
            return null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private LocalArtifactManifest() {
    }
}
