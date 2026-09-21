package ai.core.cli.hub.dataset;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "update", description = "Update one record of a GENERAL dataset")
class DatasetRecordsUpdateCommand extends DatasetWriteCommand {
    @Parameters(index = "0", paramLabel = "dataset", description = "Dataset id, or a name unique in this session")
    String dataset;

    @Option(names = "--record-id", required = true, description = "Record id to update")
    String recordId;

    @Override
    protected Integer execute() {
        var sessionId = sessionId();
        var data = dataText();
        return writeResult(datasetClient().updateRecord(sessionId, dataset, recordId, data));
    }
}
