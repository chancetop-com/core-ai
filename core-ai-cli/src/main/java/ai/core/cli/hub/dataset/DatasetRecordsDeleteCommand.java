package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "delete", description = "Delete one record of a GENERAL dataset")
class DatasetRecordsDeleteCommand extends DatasetCommandBase {
    @Parameters(index = "0", paramLabel = "dataset", description = "Dataset id, or a name unique in this session")
    String dataset;

    @Option(names = "--record-id", required = true, description = "Record id to delete")
    String recordId;

    @Override
    protected Integer execute() {
        var sessionId = sessionId();
        var response = datasetClient().deleteRecord(sessionId, dataset, recordId);
        printPayload(response.payload);
        printSummary(response.payload);
        return HubExitCodes.SUCCESS;
    }
}
