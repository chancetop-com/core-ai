package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetView;
import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.hub.HubRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.List;

/**
 * @author stephen
 */
@Command(name = "show", description = "Show one bound dataset: type, permission and schema")
class DatasetShowCommand extends DatasetCommandBase {
    @Parameters(index = "0", paramLabel = "dataset", description = "Dataset id, or a name unique in this session")
    String dataset;

    @Override
    protected Integer execute() {
        var sessionId = sessionId();
        var view = datasetClient().datasets(sessionId);
        var datasets = view.datasets == null ? List.<HubDatasetView>of() : view.datasets;
        var resolved = new DatasetRefResolver().resolve(datasets, dataset);
        if (json()) {
            HubRenderer.printJson(resolved);
        } else if (!quiet()) {
            ConsoleWriter.print(datasetRenderer.detailText(resolved));
        }
        return HubExitCodes.SUCCESS;
    }
}
