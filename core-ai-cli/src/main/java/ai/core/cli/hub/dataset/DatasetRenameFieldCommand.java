package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Renames one field of a GENERAL dataset. Inside a session the binding must allow writes; outside one the caller
 * needs {@code dataset.manage}. Records are rewritten, so the command is a write on both paths.
 *
 * @author stephen
 */
@Command(name = "rename-field", description = "Rename a field of a GENERAL dataset (records are rewritten)")
class DatasetRenameFieldCommand extends DatasetCommandBase {
    @Parameters(index = "0", paramLabel = "dataset",
            description = "Dataset id, or a name unique in this session or in the catalog")
    String dataset;

    @Option(names = "--from", required = true, description = "Current field name")
    String from;

    @Option(names = "--to", required = true, description = "New field name")
    String to;

    @Override
    protected Integer execute() {
        var sessionId = sessionIdOrNull();
        var response = sessionId != null
                ? datasetClient().renameField(sessionId, dataset, from, to)
                : datasetClient().renameDatasetField(dataset, from, to);
        printPayload(response.payload);
        printSummary(response.payload);
        return HubExitCodes.SUCCESS;
    }
}
