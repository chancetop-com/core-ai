package ai.core.cli.hub.plugin;

import java.nio.file.Path;

/**
 * Local roots for hub-installed plugin packages. The user root is what {@code CliAgent} loads at
 * user priority; the workspace root is what {@code /plugins install --local} and
 * {@code plugin install --workspace} write.
 *
 * @author stephen
 */
public final class PluginLocations {
    public static Path userPluginsDir() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "plugins");
    }

    public static Path workspacePluginsDir() {
        return Path.of(System.getProperty("user.dir"), ".core-ai", "plugins");
    }

    private PluginLocations() {
    }
}
