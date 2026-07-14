package ai.core.cli.auth;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.api.json.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * author cyril
 * description
 * createTime  2026/6/8
 **/
public record AuthConfig(@Property(name = "server_url") String serverUrl,
                         @Property(name = "api_key") String apiKey,
                         @Property(name = "user_id") String userId,
                         @Property(name = "name") String name,
                         @Property(name = "role") String role,
                         @Property(name = "login_at") String loginAt,
                         @Property(name = "active") boolean active) {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthConfig.class);
    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"), ".core-ai", "auth.json");

    public static AuthConfig login(String serverUrl, String apiKey) {
        return new AuthConfig(serverUrl, apiKey, null, null, null, Instant.now().toString(), true);
    }

    public static AuthConfig full(String serverUrl, String apiKey, String userId, String name, String role) {
        return new AuthConfig(serverUrl, apiKey, userId, name, role, Instant.now().toString(), true);
    }

    // ── Load / Save ───────────────────────────────────────────────────────

    public static AuthConfig load() {
        var all = loadAll();
        return all.stream().filter(AuthConfig::active).findFirst().orElse(null);
    }

    public static AuthConfig load(String serverUrl) {
        return loadAll().stream()
                .filter(c -> serverUrl.equals(c.serverUrl))
                .findFirst().orElse(null);
    }

    public static List<AuthConfig> loadAll() {
        if (!Files.exists(CONFIG_PATH)) return new ArrayList<>();
        try {
            var json = Files.readString(CONFIG_PATH);
            if (json.trim().startsWith("{")) {
                // Backward-compat: single object → array
                var single = JsonUtil.fromJson(AuthConfig.class, json);
                var migrated = new AuthConfig(single.serverUrl, single.apiKey, single.userId,
                        single.name, single.role, single.loginAt, true);
                var list = new ArrayList<>(List.of(migrated));
                Files.writeString(CONFIG_PATH, JsonUtil.toJson(list));
                return list;
            }
            return JsonUtil.fromJson(new TypeReference<>() { }, json);
        } catch (Exception e) {
            LOGGER.warn("Failed to load auth config: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void save() {
        var all = new ArrayList<>(loadAll());
        all.removeIf(c -> c.serverUrl.equals(this.serverUrl));
        all.replaceAll(c -> new AuthConfig(c.serverUrl, c.apiKey, c.userId, c.name, c.role, c.loginAt, false));
        all.add(this);
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, JsonUtil.toJson(all));
        } catch (IOException e) {
            LOGGER.warn("Failed to save auth config: {}", e.getMessage());
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public static AuthConfig activate(String serverUrl) {
        var all = new ArrayList<>(loadAll());
        AuthConfig activated = null;
        for (int i = 0; i < all.size(); i++) {
            var c = all.get(i);
            var newActive = c.serverUrl.equals(serverUrl);
            if (newActive) activated = c;
            all.set(i, new AuthConfig(c.serverUrl, c.apiKey, c.userId, c.name, c.role, c.loginAt, newActive));
        }
        if (activated == null) return null;
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, JsonUtil.toJson(all));
        } catch (IOException e) {
            LOGGER.warn("Failed to save auth config: {}", e.getMessage());
        }
        return activated;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public static void remove(String serverUrl) {
        var all = new ArrayList<>(loadAll());
        all.removeIf(c -> c.serverUrl.equals(serverUrl));
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, all.isEmpty() ? "[]" : JsonUtil.toJson(all));
        } catch (IOException e) {
            LOGGER.warn("Failed to remove auth config: {}", e.getMessage());
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.warn("Failed to clear auth config: {}", e.getMessage());
        }
    }
}
