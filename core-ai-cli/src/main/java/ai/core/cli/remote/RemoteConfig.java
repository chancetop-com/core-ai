package ai.core.cli.remote;

import ai.core.utils.JsonUtil;
import core.framework.api.json.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author stephen
 */
public record RemoteConfig(@Property(name = "server_url") String serverUrl,
                           @Property(name = "api_key") String apiKey,
                           @Property(name = "agent_id") String agentId) {
    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"), ".core-ai", "remote.json");

    public static RemoteConfig load() {
        if (!Files.exists(CONFIG_PATH)) return null;
        try {
            var json = Files.readString(CONFIG_PATH);
            return JsonUtil.fromJson(RemoteConfig.class, json);
        } catch (Exception e) {
            return null;
        }
    }

    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, JsonUtil.toJson(this));
        } catch (IOException e) {
            // non-critical
        }
    }

    public static void clear() {
        try {
            Files.deleteIfExists(CONFIG_PATH);
        } catch (IOException e) {
            // non-critical
        }
    }
}
