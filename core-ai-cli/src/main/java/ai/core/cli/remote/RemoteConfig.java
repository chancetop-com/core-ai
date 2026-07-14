package ai.core.cli.remote;

import ai.core.cli.auth.AuthConfig;
import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Connection configuration for a remote server — which server, which agent,
 * display name.  API credentials live separately in {@link AuthConfig}.
 *
 * <p>Stored at {@code ~/.core-ai/remote.json}.
 *
 * @author stephen
 */
public record RemoteConfig(@Property(name = "server_url") String serverUrl,
                           @Property(name = "agent_id") String agentId,
                           @Property(name = "name") String name) {
    private static final Logger LOGGER = LoggerFactory.getLogger(RemoteConfig.class);
    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"), ".core-ai", "remote.json");

    public static RemoteConfig load() {
        if (!Files.exists(CONFIG_PATH)) return null;
        try {
            var json = Files.readString(CONFIG_PATH);
            // Migrate old format that had api_key embedded
            migrateIfNeeded(json);
            return JsonUtil.fromJson(RemoteConfig.class, json);
        } catch (Exception e) {
            LOGGER.warn("Failed to load remote config: {}", e.getMessage());
            return null;
        }
    }

    /**
     * One-time migration: if remote.json still contains an api_key field,
     * extract it into auth.json so subsequent reads don't need it here.
     */
    @SuppressWarnings("unchecked")
    private static void migrateIfNeeded(String json) {
        Map<String, Object> map = JsonUtil.fromJson(Map.class, json);
        var apiKey = (String) map.get("api_key");
        var serverUrl = (String) map.get("server_url");
        if (apiKey != null && serverUrl != null) {
            var auth = AuthConfig.load(serverUrl);
            if (auth == null) {
                AuthConfig.login(serverUrl, apiKey).save();
                LOGGER.info("Migrated api_key from remote.json to auth.json");
            }
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, JsonUtil.toJson(this));
        } catch (IOException e) {
            LOGGER.warn("Failed to save remote config: {}", e.getMessage());
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(CONFIG_PATH);
        } catch (IOException e) {
            LOGGER.warn("Failed to clear remote config: {}", e.getMessage());
        }
    }
}
