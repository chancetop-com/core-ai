package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "get", description = "Read a SESSION dataset's state")
class DatasetStateGetCommand extends DatasetCommandBase {
    @Parameters(index = "0", paramLabel = "dataset", description = "Dataset id, or a name unique in this session")
    String dataset;

    @Option(names = "--fields", description = "Comma-separated top-level fields to return")
    String fields;

    @Override
    protected Integer execute() {
        var sessionId = sessionId();
        var response = datasetClient().getState(sessionId, dataset, fields);
        printPayload(response.payload);
        return HubExitCodes.SUCCESS;
    }
}
