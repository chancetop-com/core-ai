package ai.core.cli.appserver;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sidecar store for per-session model picks ({@code models.json} next to the {@code .data} files), so a
 * session keeps its own model without touching the session persistence format or the global
 * agent.properties default.
 *
 * @author stephen
 */
public class SessionModelStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionModelStore.class);
    private static final String FILE_NAME = "models.json";

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public SessionModelStore(Path sessionsDir) {
        this.file = sessionsDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            try {
                var loaded = JsonUtil.fromJson(new TypeReference<Map<String, Entry>>() { }, Files.readString(file));
                if (loaded != null) {
                    entries.putAll(loaded);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("failed to load session models from {}: {}", file, e.getMessage());
            }
        }
    }

    public Optional<Entry> model(String sessionId) {
        return Optional.ofNullable(entries.get(sessionId));
    }

    public void set(String sessionId, String model, String provider) {
        var entry = new Entry();
        entry.model = model;
        entry.provider = provider;
        entries.put(sessionId, entry);
        save();
    }

    public void remove(String sessionId) {
        if (entries.remove(sessionId) != null) {
            save();
        }
    }

    private void save() {
        try {
            var parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, JsonUtil.toJson(entries));
        } catch (IOException e) {
            LOGGER.warn("failed to save session models to {}: {}", file, e.getMessage());
        }
    }

    public static class Entry {
        public String model;
        public String provider;
    }
}
