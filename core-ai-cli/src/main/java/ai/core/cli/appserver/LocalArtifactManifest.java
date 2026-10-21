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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only registry of files that belong to the artifact panel: one JSON object per line under
 * {@code {workspace}/.core-ai/artifacts/manifest.jsonl}. Media saved by {@link LocalArtifactSink} and files
 * registered through {@code submit_artifacts} both land here with their owning session, so a reopened panel
 * can scope itself to a conversation. {@link LocalArtifacts} merges the entries into the panel list and the
 * desktop serves registered paths outside {@code .core-ai} through the artifact protocol — path and format
 * are a cross-process contract with the desktop (see its {@code artifactPath.ts}).
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

    /** Registered paths with their sessions, duplicates collapsed (the last registration wins). */
    static List<Entry> entries(Path workspace) {
        var file = file(workspace);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        try {
            for (var line : Files.readAllLines(file)) {
                var entry = parse(line);
                if (entry != null) {
                    entries.put(entry.path(), entry);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("failed to read the artifact manifest {}: {}", file, e.getMessage());
        }
        return List.copyOf(entries.values());
    }

    static void append(Path workspace, String path, String sessionId) throws IOException {
        var file = file(workspace);
        var parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        var line = JsonUtil.OBJECT_MAPPER.createObjectNode().put("path", path);
        if (sessionId != null && !sessionId.isBlank()) {
            line.put("sessionId", sessionId);
        }
        line.put("registeredAt", Instant.now().toString());
        synchronized (APPEND_LOCK) {
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        }
    }

    private static Entry parse(String line) {
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(line);
            var path = node == null ? null : node.get("path");
            if (path == null || !path.isTextual() || path.asText().isBlank()) {
                return null;
            }
            var session = node.get("sessionId");
            var sessionId = session != null && session.isTextual() && !session.asText().isBlank()
                    ? session.asText() : null;
            return new Entry(path.asText(), sessionId);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private LocalArtifactManifest() {
    }

    /** One registered artifact; a null session means the line predates session attribution. */
    record Entry(String path, String sessionId) {
    }
}
