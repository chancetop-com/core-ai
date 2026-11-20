package ai.core.cli.hub.plugin;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "show", description = "Show one registry plugin: identity, digest and manifest")
class PluginShowCommand extends HubCommandBase {
    private final PluginHubRenderer pluginRenderer = new PluginHubRenderer();

    @Parameters(index = "0", paramLabel = "id", description = "Registry plugin id")
    String id;

    @Override
    protected Integer execute() {
        var plugin = pluginClient().get(id);
        if (json()) {
            HubRenderer.printJson(plugin);
        } else if (raw()) {
            ConsoleWriter.print(plugin.manifest == null ? "" : plugin.manifest);
        } else {
            pluginRenderer.print(pluginRenderer.detailText(plugin));
        }
        return HubExitCodes.SUCCESS;
    }
}
