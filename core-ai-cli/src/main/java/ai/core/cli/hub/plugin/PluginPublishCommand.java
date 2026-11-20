package ai.core.cli.hub.plugin;

import ai.core.api.server.plugin.RegisterPluginRequest;
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

/**
 * Publishes a local package to the registry: the manifest and digest come from the zip, and the
 * caller supplies the HTTPS URL that serves those same bytes.
 *
 * @author stephen
 */
@Command(name = "publish", description = "Register a local plugin package by its download URL and digest")
class PluginPublishCommand extends HubCommandBase {
    private static final long MAX_PACKAGE_BYTES = 256L * 1024 * 1024;

    @Parameters(index = "0", paramLabel = "zip", description = "Local plugin package (.zip)")
    Path archive;

    @Option(names = "--download-url", required = true, description = "HTTPS URL serving this exact package")
    String downloadUrl;

    @Option(names = "--vendor", description = "Vendor display name")
    String vendor;

    @Option(names = "--homepage", description = "Project homepage")
    String homepage;

    @Override
    protected Integer execute() {
        if (!Files.isRegularFile(archive)) {
            throw new HubCliError(HubExitCodes.USAGE, "package not found: " + archive);
        }
        try {
            var size = Files.size(archive);
            if (size <= 0 || size > MAX_PACKAGE_BYTES) {
                throw new HubCliError(HubExitCodes.USAGE, "package must be between 1 byte and 256 MB");
            }
            var bytes = Files.readAllBytes(archive);
            var request = new RegisterPluginRequest();
            request.manifest = PluginPackage.readManifest(bytes);
            request.downloadUrl = downloadUrl.trim();
            request.sha256 = PluginHubClient.sha256(bytes);
            request.size = size;
            request.vendor = vendor;
            request.homepage = homepage;
            var plugin = pluginClient().register(request);
            if (json()) {
                HubRenderer.printJson(plugin);
            } else {
                ConsoleWriter.println("Published " + plugin.name + " v" + plugin.version + " (" + plugin.id + ")");
            }
            return HubExitCodes.SUCCESS;
        } catch (IOException e) {
            throw new IllegalStateException("plugin publish failed: " + e.getMessage(), e);
        }
    }
}
