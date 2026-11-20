package ai.core.cli.hub.plugin;

import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "search", description = "Search the desktop plugin registry (query omitted: list all)")
class PluginSearchCommand extends HubCommandBase {
    private final PluginHubRenderer pluginRenderer = new PluginHubRenderer();

    @Parameters(index = "0", arity = "0..1", paramLabel = "query", description = "Free-text search terms")
    String query;

    @Option(names = "--offset", description = "Skip this many entries")
    Integer offset;

    @Option(names = "--limit", description = "Max results (default 50, max 100)")
    Integer limit;

    @Override
    protected Integer execute() {
        var response = pluginClient().search(query, offset, limit);
        if (json()) HubRenderer.printJson(response);
        else pluginRenderer.print(pluginRenderer.searchText(response));
        return HubExitCodes.SUCCESS;
    }
}
