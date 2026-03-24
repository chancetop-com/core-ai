package ai.core.a2a;

import ai.core.api.a2a.Artifact;
import ai.core.api.a2a.Message;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.server.session.AgentSession;

import java.util.List;

/**
 * @author stephen
 */
public class A2ATaskState {
    public final String taskId;
    public final String contextId;
    public final AgentSession session;

    private volatile TaskState state = TaskState.SUBMITTED;
    private volatile String awaitCallId;
    private volatile String awaitTool;
    private volatile String awaitArguments;
    private final StringBuilder outputBuffer = new StringBuilder();

    public volatile Long inputTokens;
    public volatile Long outputTokens;
    public volatile String errorMessage;

    public A2ATaskState(String taskId, String contextId, AgentSession session) {
        this.taskId = taskId;
        this.contextId = contextId;
        this.session = session;
    }

    public TaskState getState() {
        return state;
    }

    public void setState(TaskState state) {
        this.state = state;
    }

    public String getAwaitCallId() {
        return awaitCallId;
    }

    public void setAwait(String callId, String tool, String arguments) {
        this.awaitCallId = callId;
        this.awaitTool = tool;
        this.awaitArguments = arguments;
    }

    public void clearAwait() {
        this.awaitCallId = null;
        this.awaitTool = null;
        this.awaitArguments = null;
    }

    public String getAwaitTool() {
        return awaitTool;
    }

    public String getAwaitArguments() {
        return awaitArguments;
    }

    public void appendOutput(String chunk) {
        synchronized (outputBuffer) {
            outputBuffer.append(chunk);
        }
    }

    public String getFullOutput() {
        synchronized (outputBuffer) {
            return outputBuffer.toString();
        }
    }

    public Task toTask() {
        var task = new Task();
        task.id = taskId;
        task.contextId = contextId;
        task.status = TaskStatus.of(state);
        var output = getFullOutput();
        if (!output.isEmpty()) {
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
