package ai.core.cli.hub.dataset;

import ai.core.api.server.hub.HubDatasetOpResponse;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/**
 * Write leaves carry a JSON object either inline ({@code --data}) or through a file ({@code --data-file}), where
 * {@code -} reads stdin so a script can pipe a payload larger than a command line.
 *
 * @author stephen
 */
abstract class DatasetWriteCommand extends DatasetCommandBase {
    @Option(names = "--data", description = "JSON object text of the fields to write")
    String data;

    @Option(names = "--data-file", description = "Read the JSON object from a file ('-' for stdin)")
    Path dataFile;

    protected String dataText() {
        return new DatasetDataInput().read(data, dataFile);
    }

    protected Integer writeResult(HubDatasetOpResponse response) {
        printPayload(response.payload);
        printSummary(response.payload);
        return HubExitCodes.SUCCESS;
    }
}
