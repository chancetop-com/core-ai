package ai.core.cli.tool;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/**
 * @author stephen
 */
@Command(name = "task", description = "Poll an async local builtin task, e.g. a generate_video task id")
class ToolTaskCommand extends ToolCommandBase {
    @Parameters(index = "0", paramLabel = "task-id", description = "Task id returned by an async builtin tool")
    String taskId;

    @Override
    protected Integer execute() {
        var startedAt = System.currentTimeMillis();
        var result = LocalToolRuntime.pollVideoTask(workspaceDirectory(), credentialsOrNull(), configFile, taskId);
        return print(response(result, taskId, System.currentTimeMillis() - startedAt));
    }
}
