package ai.core.cli.hub.dataset;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "patch", description = "Merge the given fields into a SESSION dataset's state")
class DatasetStatePatchCommand extends DatasetWriteCommand {
    @Parameters(index = "0", paramLabel = "dataset", description = "Dataset id, or a name unique in this session")
    String dataset;

    @Override
    protected Integer execute() {
        var sessionId = sessionId();
        var data = dataText();
        return writeResult(datasetClient().patchState(sessionId, dataset, data));
    }
}
