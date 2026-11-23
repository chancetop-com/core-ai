package ai.core.cli.hub.dataset;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "insert", description = "Insert one record into a GENERAL dataset")
class DatasetRecordsInsertCommand extends DatasetWriteCommand {
    @Parameters(index = "0", paramLabel = "dataset",
            description = "Dataset id, or a name unique in this session or in the catalog")
    String dataset;

    @Override
    protected Integer execute() {
        var sessionId = sessionIdOrNull();
        var data = dataText();
        return writeResult(sessionId != null
                ? datasetClient().insertRecord(sessionId, dataset, data)
                : datasetClient().insertDatasetRecord(dataset, data));
    }
}
