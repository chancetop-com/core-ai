package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetListView;
import ai.core.api.server.hub.HubDatasetView;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * @author stephen
 */
@Command(name = "list", description = "List datasets: a session's bindings, or the catalog when no session is named")
class DatasetListCommand extends DatasetCommandBase {
    @Option(names = "--query", description = "Filter by name, description or owner (catalog scope only)")
    String query;

    @Option(names = "--limit", description = "Catalog page size, max 100 (default 100; catalog scope only)")
    Integer limit;

    @Option(names = "--offset", description = "Catalog page offset (catalog scope only)")
    Integer offset;

    @Override
    protected Integer execute() {
        var sessionId = sessionIdOrNull();
        HubDatasetListView view;
        String emptyText;
        if (sessionId != null) {
            view = datasetClient().datasets(sessionId);
            emptyText = "  (no datasets bound to this session)\n";
        } else {
            var pageSize = limit;
            if (pageSize == null) pageSize = 100;
            view = datasetClient().datasets(query, offset, pageSize);
            emptyText = "  (no datasets)\n";
        }
        if (json()) {
            HubRenderer.printJson(view);
            return HubExitCodes.SUCCESS;
        }
        if (!quiet()) {
            ConsoleWriter.print(datasetRenderer.listText(
                    view.datasets == null ? List.<HubDatasetView>of() : view.datasets, emptyText));
        }
        return HubExitCodes.SUCCESS;
    }
}
