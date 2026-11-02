package ai.core.cli.appserver;

import ai.core.utils.AtomicFiles;
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
 * Sidecar store for user-renamed session titles ({@code titles.json} next to the {@code .data} files),
 * so a rename never touches the session persistence format.
 *
 * @author stephen
 */
public class SessionTitleStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionTitleStore.class);
    private static final String FILE_NAME = "titles.json";

    private final Path file;
    private final Map<String, String> titles = new ConcurrentHashMap<>();

    public SessionTitleStore(Path sessionsDir) {
        this.file = sessionsDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            try {
                var loaded = JsonUtil.fromJson(new TypeReference<Map<String, String>>() { }, Files.readString(file));
                if (loaded != null) {
                    titles.putAll(loaded);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("failed to load session titles from {}: {}", file, e.getMessage());
                quarantine();
            }
        }
    }

    public Optional<String> title(String sessionId) {
        return Optional.ofNullable(titles.get(sessionId));
    }

    public void set(String sessionId, String title) {
        if (title == null || title.isBlank()) {
            titles.remove(sessionId);
        } else {
            titles.put(sessionId, title);
        }
        save();
    }

    public void remove(String sessionId) {
        if (titles.remove(sessionId) != null) {
            save();
        }
    }

    public java.util.Set<String> ids() {
        return java.util.Set.copyOf(titles.keySet());
    }

    public void retainOnly(java.util.Set<String> liveIds) {
        var changed = titles.keySet().removeIf(id -> !liveIds.contains(id));
        if (changed) save();
    }

    private void save() {
        try {
            AtomicFiles.writeString(file, JsonUtil.toJson(titles));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to save session titles to {}: {}", file, e.getMessage());
        }
    }

    private void quarantine() {
        try {
            if (Files.exists(file)) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".bad-" + System.currentTimeMillis()));
            }
        } catch (IOException e) {
            LOGGER.warn("failed to quarantine corrupt session titles file {}: {}", file, e.getMessage());
        }
    }
}
