package ai.core.cli.hub.plugin;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.command.plugins.PluginDiscovery;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removes a locally installed plugin package, matched by directory name or manifest name.
 *
 * @author stephen
 */
@Command(name = "remove", description = "Remove a locally installed plugin package")
class PluginRemoveCommand extends HubCommandBase {
    private final PluginDiscovery discovery = new PluginDiscovery();

    @Parameters(index = "0", paramLabel = "id|name", description = "Plugin id or manifest name")
    String pluginRef;

    @Option(names = "--workspace", description = "Only remove from {cwd}/.core-ai/plugins")
    boolean workspace;

    @Override
    protected Integer execute() {
        var roots = workspace
                ? List.of(PluginLocations.workspacePluginsDir())
                : List.of(PluginLocations.userPluginsDir(), PluginLocations.workspacePluginsDir());
        var matches = new ArrayList<Path>();
        for (var root : roots) findMatches(root, matches);
        if (matches.isEmpty()) {
            throw new HubCliError(HubExitCodes.NOT_FOUND, "plugin not installed: " + pluginRef);
        }
        if (matches.size() > 1) {
            var paths = String.join(", ", matches.stream().map(Path::toString).toList());
            throw new HubCliError(HubExitCodes.USAGE,
                    "plugin exists in more than one root (" + paths + "); pass --workspace to choose the workspace copy");
        }
        var target = matches.getFirst();
        try {
            PluginPackage.deleteRecursively(target);
            if (json()) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("removed", target.toAbsolutePath().toString());
                HubRenderer.printJson(output);
            } else {
                ConsoleWriter.println("Removed " + target);
            }
            return HubExitCodes.SUCCESS;
        } catch (IOException e) {
            throw new IllegalStateException("plugin remove failed: " + e.getMessage(), e);
        }
    }

    private void findMatches(Path root, List<Path> matches) {
        if (!Files.isDirectory(root)) return;
        try (var stream = Files.list(root)) {
            for (var dir : stream.filter(Files::isDirectory).sorted().toList()) {
                var manifest = discovery.loadPluginManifest(dir);
                var name = manifest == null ? null : manifest.name();
                var fileName = dir.getFileName();
                if (fileName != null && pluginRef.equals(fileName.toString())) {
                    matches.add(dir);
                } else if (pluginRef.equals(name)) {
                    matches.add(dir);
                }
            }
        } catch (IOException ignored) {
            // an unreadable root contributes nothing
        }
    }
}
