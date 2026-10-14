package ai.core.cli.helper;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File-backed user-level helper configuration ({@code ~/.core-ai/helpers.json}). Pure
 * persistence — the validation rules live in {@link HelperService}; writes are atomic
 * (temp file + move) so a crash cannot leave a truncated config behind.
 *
 * @author stephen
 */
public class HelperConfigStore {
    public static final int MAX_HELPERS = 5;

    public static HelperConfigStore defaultStore() {
        return new HelperConfigStore(Path.of(System.getProperty("user.home"), ".core-ai", "helpers.json"));
    }

    private static HelperEntry copyOf(HelperEntry source) {
        var copy = new HelperEntry();
        copy.agentId = source.agentId;
        copy.name = source.name;
        copy.description = source.description;
        copy.whenToUse = source.whenToUse;
        copy.enabled = source.enabled;
        copy.addedAt = source.addedAt;
        return copy;
    }

    private final Path file;
    private final HelperConfig config = new HelperConfig();
    private final Object lock = new Object();

    public HelperConfigStore(Path file) {
        this.file = file;
        load();
    }

    public List<HelperEntry> list() {
        synchronized (lock) {
            var copies = new ArrayList<HelperEntry>(config.helpers.size());
            for (var entry : config.helpers) {
                copies.add(copyOf(entry));
            }
            return copies;
        }
    }

    public HelperEntry find(String agentId) {
        synchronized (lock) {
            var entry = findEntry(agentId);
            return entry == null ? null : copyOf(entry);
        }
    }

    public void add(HelperEntry entry) {
        synchronized (lock) {
            config.helpers.add(copyOf(entry));
            persist();
        }
    }

    /** Blank {@code whenToUse} clears the hint; {@code null} leaves it unchanged. */
    public boolean update(String agentId, String whenToUse, Boolean enabled) {
        synchronized (lock) {
            var entry = findEntry(agentId);
            if (entry == null) {
                return false;
            }
            if (whenToUse != null) {
                entry.whenToUse = whenToUse.isBlank() ? null : whenToUse;
            }
            if (enabled != null) {
                entry.enabled = enabled;
            }
            persist();
            return true;
        }
    }

    public boolean remove(String agentId) {
        synchronized (lock) {
            var entry = findEntry(agentId);
            if (entry == null) {
                return false;
            }
            config.helpers.remove(entry);
            persist();
            return true;
        }
    }

    private HelperEntry findEntry(String agentId) {
        if (agentId == null) {
            return null;
        }
        for (var entry : config.helpers) {
            if (agentId.equals(entry.agentId)) {
                return entry;
            }
        }
        return null;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            var content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return;
            }
            var loaded = JsonUtil.fromJson(HelperConfig.class, content);
            if (loaded == null || loaded.helpers == null) {
                return;
            }
            for (var entry : loaded.helpers) {
                if (entry != null && entry.agentId != null) {
                    config.helpers.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load helpers, file=" + file, e);
        }
    }

    private void persist() {
        try {
            var parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            var tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JsonUtil.toJson(config), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist helpers, file=" + file, e);
        }
    }
}
