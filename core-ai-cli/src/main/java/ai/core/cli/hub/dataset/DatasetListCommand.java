package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetView;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;

import java.util.List;

/**
 * @author stephen
 */
@Command(name = "list", description = "List the datasets bound to a session")
class DatasetListCommand extends DatasetCommandBase {
    @Override
    protected Integer execute() {
        var sessionId = sessionId();
        var view = datasetClient().datasets(sessionId);
        if (json()) {
            HubRenderer.printJson(view);
            return HubExitCodes.SUCCESS;
        }
        if (!quiet()) {
            ConsoleWriter.print(datasetRenderer.listText(view.datasets == null ? List.<HubDatasetView>of() : view.datasets));
        }
        return HubExitCodes.SUCCESS;
    }
}
