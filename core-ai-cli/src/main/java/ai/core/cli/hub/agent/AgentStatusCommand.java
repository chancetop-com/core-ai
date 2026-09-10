package ai.core.cli.hub.agent;

import ai.core.cli.hub.HubCommandBase;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * Polls a task that is still running (the {@code running} answer of {@code agent run}).
 *
 * @author stephen
 */
@Command(name = "status", description = "Show the current state of a run (task_id of a previous run)")
class AgentStatusCommand extends HubCommandBase {
    @Parameters(index = "0", paramLabel = "task_id", description = "The task_id returned by run/reply")
    String taskId;

    @Override
    protected Integer execute() {
        var result = agentClient().status(taskId);
        return new RunResultRenderer().render(result, Integer.MAX_VALUE, json());
    }
}
