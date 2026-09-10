package ai.core.cli.hub.agent;

import ai.core.api.server.agenthub.AgentHubReplyRequest;
import ai.core.cli.hub.HubCliError;
import ai.core.cli.hub.HubCommandBase;
import ai.core.cli.hub.HubExitCodes;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Answers a task that is waiting for input: approve or deny a pending tool call, or send the
 * missing information as a new turn on the same conversation.
 *
 * @author stephen
 */
@Command(name = "reply", description = "Answer a run that is waiting for input (approve/deny a tool, or send a message)")
class AgentReplyCommand extends HubCommandBase {
    private static final int DEFAULT_MAX_OUTPUT = 20000;

    @Parameters(index = "0", paramLabel = "task_id", description = "The task_id returned by run")
    String taskId;

    @Option(names = "--approve", description = "Approve the pending tool call")
    boolean approve;

    @Option(names = "--deny", description = "Deny the pending tool call")
    boolean deny;

    @Option(names = "--message", description = "Answer with text instead (a new turn on the same conversation)")
    String message;

    @Option(names = "--max-output", description = "Truncate printed output after N chars (default 20000)")
    Integer maxOutput;

    @Override
    protected Integer execute() {
        var request = new AgentHubReplyRequest();
        request.decision = decision();
        request.message = message != null && !message.isBlank() ? message : null;
        metadata("replying to " + taskId + " ...");
        var result = agentClient().reply(taskId, request);
        return new RunResultRenderer().render(result, outputLimit(), json());
    }

    private String decision() {
        int given = (approve ? 1 : 0) + (deny ? 1 : 0) + (message != null && !message.isBlank() ? 1 : 0);
        if (given != 1) {
            throw new HubCliError(HubExitCodes.USAGE, "pass exactly one of --approve, --deny or --message");
        }
        if (approve) return "approve";
        if (deny) return "deny";
        return null;
    }

    private int outputLimit() {
        return maxOutput == null || maxOutput <= 0 ? DEFAULT_MAX_OUTPUT : maxOutput;
    }
}
