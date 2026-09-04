package ai.core.cli.hub;

import ai.core.cli.ConsoleWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "search", description = "Search the MCP tool catalog (query omitted: list all)")
class McpSearchCommand extends HubCommandBase {
    @Parameters(index = "0", arity = "0..1", paramLabel = "query", description = "Free-text search terms")
    String query;

    @Option(names = "--on-server", description = "Only search tools of this server")
    String serverFilter;

    @Option(names = "--limit", description = "Max results (default 20, max 200)")
    Integer limit;

    @Override
    protected Integer execute() {
        var response = client().search(query, serverFilter, limit);
        if (options.json) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(renderer.searchText(response));
        }
        return HubExitCodes.SUCCESS;
    }
}
