package ai.core.cli.hub.dataset;

import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "query", description = "Query records of a GENERAL dataset")
class DatasetRecordsQueryCommand extends DatasetCommandBase {
    @Parameters(index = "0", paramLabel = "dataset", description = "Dataset id, or a name unique in this session")
    String dataset;

    @Option(names = "--filter", description = "Filter as a JSON object, e.g. '{\"status\":\"open\"}'")
    String filter;

    @Option(names = "--fields", description = "Comma-separated fields to return")
    String fields;

    @Option(names = "--from", description = "Lower bound on created_at (ISO-8601, inclusive)")
    String from;

    @Option(names = "--to", description = "Upper bound on created_at (ISO-8601, inclusive)")
    String to;

    @Option(names = "--limit", description = "Maximum records to return")
    Integer limit;

    @Option(names = "--offset", description = "Records to skip")
    Integer offset;

    @Override
    protected Integer execute() {
        var sessionId = sessionId();
        var query = new DatasetHubClient.RecordQuery(filter, fields, from, to, limit, offset);
        var response = datasetClient().queryRecords(sessionId, dataset, query);
        printPayload(response.payload);
        return HubExitCodes.SUCCESS;
    }
}
