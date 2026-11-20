package ai.core.cli.hub.plugin;

import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import ai.core.cli.command.plugins.PluginDiscovery;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists plugin packages installed on this machine, from the same roots the agent loads.
 *
 * @author stephen
 */
@Command(name = "list", description = "List locally installed plugin packages")
class PluginListCommand extends HubCommandBase {
    private final PluginHubRenderer pluginRenderer = new PluginHubRenderer();
    private final PluginDiscovery discovery = new PluginDiscovery();

    @Option(names = "--workspace", description = "Only list {cwd}/.core-ai/plugins")
    boolean workspace;

    @Override
    protected Integer execute() {
        var roots = workspace
                ? List.of(PluginLocations.workspacePluginsDir())
                : List.of(PluginLocations.userPluginsDir(), PluginLocations.workspacePluginsDir());
        var plugins = new ArrayList<PluginDiscovery.PluginEntry>();
        for (var root : roots) plugins.addAll(scan(root));
        if (json()) {
            var output = new ArrayList<Map<String, Object>>(plugins.size());
            for (var plugin : plugins) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", plugin.name());
                item.put("version", plugin.version());
                item.put("description", plugin.description());
                item.put("path", plugin.path().toAbsolutePath().toString());
                output.add(item);
            }
            HubRenderer.printJson(Map.of("plugins", output));
        } else {
            pluginRenderer.print(pluginRenderer.localText(plugins));
        }
        return HubExitCodes.SUCCESS;
    }

    private List<PluginDiscovery.PluginEntry> scan(Path root) {
        if (!Files.isDirectory(root)) return List.of();
        var entries = new ArrayList<PluginDiscovery.PluginEntry>();
        try (var stream = Files.list(root)) {
            for (var dir : stream.filter(Files::isDirectory).sorted().toList()) {
                var manifest = discovery.loadPluginManifest(dir);
                if (manifest != null) {
                    entries.add(new PluginDiscovery.PluginEntry(manifest.name(), dir, manifest.version(), manifest.description()));
                }
            }
        } catch (IOException ignored) {
            // an unreadable root contributes nothing
        }
        return entries;
    }
}
