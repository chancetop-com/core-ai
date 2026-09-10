package ai.core.cli.hub.agent;

import ai.core.cli.hub.HubCommandBase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Cancels a task that is still running or waiting for input; already finished tasks report their
 * final state unchanged.
 *
 * @author stephen
 */
@Command(name = "cancel", description = "Cancel a run (task_id of a previous run)")
class AgentCancelCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "task_id", description = "The task_id returned by run/reply")
    String taskId;

    @Override
    protected Integer execute() {
        metadata("cancelling " + taskId + " ...");
        var result = agentClient().cancel(taskId);
        return new RunResultRenderer().render(result, Integer.MAX_VALUE, json());
    }
}
