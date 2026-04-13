package ai.core.cli.command.plugins;

import ai.core.utils.JsonUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovers and loads plugin manifests from plugin directories.
 */
public class PluginDiscovery {

    private static final String[] PLUGIN_DIRS = {
        ".core-ai/plugins",
        System.getProperty("user.home") + "/.core-ai/plugins"
    };

    /**
     * Scans all plugin directories for plugins.
     */
    public List<PluginEntry> scanPlugins() {
        var result = new ArrayList<PluginEntry>();
        for (String dir : PLUGIN_DIRS) {
            var path = Path.of(dir);
            if (!Files.isDirectory(path)) continue;
            try (var stream = Files.list(path)) {
                stream.filter(Files::isDirectory)
                      .sorted()
                      .forEach(p -> {
                          var manifest = loadPluginManifest(p);
                          if (manifest != null) {
                              result.add(new PluginEntry(
                                  manifest.name(),
                                  p,
                                  manifest.version(),
                                  manifest.description()
                              ));
                          }
                      });
            } catch (IOException ignored) {
                // skip unreadable directories
            }
        }
        return result;
    }

    /**
     * Loads plugin manifest from a plugin directory.
     * Checks both .claude-plugin/plugin.json and plugin.json formats.
     */
    public PluginManifest loadPluginManifest(Path pluginDir) {
        var manifestFile = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
        if (!Files.exists(manifestFile)) {
            manifestFile = pluginDir.resolve("plugin.json");
        }

        if (!Files.exists(manifestFile)) {
            return null;
        }

        try {
            String content = Files.readString(manifestFile, StandardCharsets.UTF_8);
            var map = JsonUtil.fromJson(Map.class, content);
            return new PluginManifest(
                (String) map.get("name"),
                (String) map.get("version"),
                (String) map.get("description"),
                (String) map.get("author"),
                (String) map.get("license")
            );
        } catch (Exception e) {
            return null;
        }
    }

    public record PluginEntry(String name, Path path, String version, String description) { }

    public record PluginManifest(String name, String version, String description, String author, String license) { }
}
