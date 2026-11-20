package ai.core.cli.hub.plugin;

import ai.core.cli.ConsoleWriter;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Downloads the package pinned by the registry, verifies its digest and installs it into the same
 * plugin roots the local {@code /plugins install} command uses.
 *
 * @author stephen
 */
@Command(name = "install", description = "Download a registry plugin package and install it locally")
class PluginInstallCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "id", description = "Registry plugin id")
    String id;

    @Option(names = "--workspace", description = "Install into {cwd}/.core-ai/plugins (default: ~/.core-ai/plugins)")
    boolean workspace;

    @Option(names = "--force", description = "Replace an existing installation")
    boolean force;

    @Override
    protected Integer execute() {
        var client = pluginClient();
        var plugin = client.get(id);
        var root = workspace ? PluginLocations.workspacePluginsDir() : PluginLocations.userPluginsDir();
        var target = root.resolve(plugin.id);
        if (Files.exists(target) && !force) {
            throw new HubCliError(HubExitCodes.USAGE,
                    "plugin already installed at " + target + " (pass --force to replace it)");
        }
        Path staging = null;
        try {
            Files.createDirectories(root);
            var archive = client.download(plugin);
            staging = Files.createTempDirectory(root, ".install-");
            var installed = PluginPackage.install(archive, staging);
            PluginPackage.publish(staging, target);
            if (json()) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("id", plugin.id);
                output.put("version", plugin.version);
                output.put("status", "installed");
                output.put("installed_to", target.toAbsolutePath().toString());
                output.put("files", installed.files());
                HubRenderer.printJson(output);
            } else {
                ConsoleWriter.println("Installed " + plugin.name + " v" + plugin.version + " to " + target
                        + " (" + installed.files() + " files)");
            }
            return HubExitCodes.SUCCESS;
        } catch (IOException e) {
            throw new IllegalStateException("plugin install failed: " + e.getMessage(), e);
        } finally {
            cleanup(staging);
        }
    }

    private void cleanup(Path staging) {
        if (staging == null) return;
        try {
            PluginPackage.deleteRecursively(staging);
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
