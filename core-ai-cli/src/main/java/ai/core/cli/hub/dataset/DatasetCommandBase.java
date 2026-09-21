package ai.core.cli.hub.dataset;

import ai.core.cli.ConsoleWriter;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import ai.core.cli.http.RemoteApiException;
import picocli.CommandLine.Option;

import java.time.Duration;

/**
 * Shared plumbing of the {@code core-ai-cli dataset} leaves: session resolution ({@code --session} or
 * {@code CORE_AI_SESSION_ID}) and the client that reaches that session's datasets over the hub endpoints.
 * <p>
 * Payload text is data: json/raw mode prints it verbatim (bytes identical to what the agent tools return), human mode
 * pretty prints it and adds a one-line outcome summary on stderr, so stdout stays pipeable.
 *
 * @author stephen
 */
abstract class DatasetCommandBase extends HubCommandBase {
    @Option(names = "--session", description = "Session id (defaults to CORE_AI_SESSION_ID)")
    String session;

    @Option(names = "--timeout", description = "Request timeout in seconds (default 60)")
    Integer timeoutSeconds;

    protected final DatasetRenderer datasetRenderer = new DatasetRenderer();

    protected String sessionId() {
        return new DatasetSessionResolver().resolve(session, System.getenv());
    }

    protected DatasetHubClient datasetClient() {
        var timeout = timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds);
        return new DatasetHubClient(serverUrl(), credentials().apiKey(), insecure(), timeout);
    }

    protected void printPayload(String payload) {
        if (quiet() || payload == null) return;
        if (json() || raw()) {
            ConsoleWriter.println(payload);
            return;
        }
        ConsoleWriter.print(datasetRenderer.pretty(payload));
        if (!payload.endsWith("\n")) ConsoleWriter.println();
    }

    protected void printSummary(String payload) {
        var summary = datasetRenderer.summary(payload);
        if (summary != null) metadata(summary);
    }

    /**
     * A rejected dataset operation is a usage error, not a tool failure: 400 always means the request itself is
     * wrong (bad filter, bad data, ambiguous name, wrong dataset type), and a script must fix the call rather
     * than retry it. The generic hub table would report it as {@code TOOL_ERROR}.
     */
    @Override
    protected int exitCodeFor(RemoteApiException error) {
        return error.statusCode == 400 ? HubExitCodes.USAGE : HubExitCodes.forException(error);
    }
}
