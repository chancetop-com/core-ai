package ai.core.cli.hub.apitool;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "search", description = "Search the Service API catalog (query omitted: list all)")
class ApiToolSearchCommand extends HubCommandBase {
    @Parameters(index = "0", arity = "0..1", paramLabel = "query", description = "Free-text search terms")
    String query;

    @Option(names = "--on-app", description = "Only search operations of this app (lifts the per-app cap)")
    String appFilter;

    @Option(names = "--service", description = "Only search operations of this service")
    String serviceFilter;

    @Option(names = "--limit", description = "Max results (default 20, max 200)")
    Integer limit;

    @Override
    protected Integer execute() {
        var response = apiToolClient().search(query, appFilter, serviceFilter, limit);
        if (json()) {
            HubRenderer.printJson(response);
        } else {
            ConsoleWriter.print(renderer.apiSearchText(response));
        }
        return HubExitCodes.SUCCESS;
    }
}
