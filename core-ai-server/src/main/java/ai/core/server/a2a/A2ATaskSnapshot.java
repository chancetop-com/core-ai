package ai.core.server.a2a;

import ai.core.a2a.A2ATaskState;
import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import core.framework.api.json.Property;

import java.util.List;

/**
 * Redis-backed, lightweight A2A task view shared across server Pods.
 *
 * @author xander
 */
public class A2ATaskSnapshot {
    public static A2ATaskSnapshot from(A2ATaskState state, String ownerPod) {
        var snapshot = new A2ATaskSnapshot();
        snapshot.taskId = state.taskId;
        snapshot.contextId = state.contextId;
        snapshot.ownerPod = ownerPod;
        snapshot.state = state.getState();
        snapshot.output = state.getFullOutput();
        snapshot.awaitCallId = state.getAwaitCallId();
        snapshot.awaitTool = state.getAwaitTool();
        snapshot.awaitArguments = state.getAwaitArguments();
        snapshot.errorMessage = state.errorMessage;
        snapshot.updatedAtMillis = state.updatedAtMillis();
        return snapshot;
    }

    @Property(name = "taskId")
    public String taskId;

    @Property(name = "contextId")
    public String contextId;

    @Property(name = "ownerPod")
    public String ownerPod;

    @Property(name = "state")
    public TaskState state;

    @Property(name = "output")
    public String output;

    @Property(name = "awaitCallId")
    public String awaitCallId;

    @Property(name = "awaitTool")
    public String awaitTool;

    @Property(name = "awaitArguments")
    public String awaitArguments;

    @Property(name = "errorMessage")
    public String errorMessage;

    @Property(name = "updatedAtMillis")
    public Long updatedAtMillis;

    public boolean isTerminal() {
        return state == TaskState.COMPLETED
                || state == TaskState.FAILED
                || state == TaskState.CANCELED
                || state == TaskState.REJECTED;
    }

    public Task toTask() {
        var task = new Task();
        task.id = taskId;
        task.contextId = contextId;
        task.status = TaskStatus.of(state);
        if (output != null && !output.isEmpty()) {
            task.artifacts = List.of(Artifact.text(output));
        }
        if (state == TaskState.INPUT_REQUIRED && awaitCallId != null) {
            var detail = awaitTool != null ? awaitTool : "tool approval required";
            task.status = TaskStatus.of(state, Message.agent("Tool requires approval: " + detail));
        }
        if (errorMessage != null) {
            task.status = TaskStatus.of(TaskState.FAILED, Message.agent(errorMessage));
        }
        return task;
    }
}
