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

    private void save() {
        try {
            var parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, JsonUtil.toJson(titles));
        } catch (IOException e) {
            LOGGER.warn("failed to save session titles to {}: {}", file, e.getMessage());
        }
    }
}
