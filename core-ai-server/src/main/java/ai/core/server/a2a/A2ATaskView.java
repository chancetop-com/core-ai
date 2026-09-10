package ai.core.server.a2a;

import ai.core.a2a.A2ATaskState;
import ai.core.api.a2a.Task;

/**
 * A task as seen by a caller that is not speaking the raw A2A protocol: the protocol
 * {@link Task} plus the fields the A2A {@code Task} payload does not carry (token usage and the
 * tool approval the task is waiting for). Local tasks expose the live state, remote ones the
 * shared snapshot.
 *
 * @author stephen
 */
public record A2ATaskView(Task task, String contextId, Long inputTokens, Long outputTokens,
                          String awaitCallId, String awaitTool, String awaitArguments, String errorMessage) {
    /** View for a task whose owner pod only reported the protocol payload. */
    static A2ATaskView ofTask(Task task) {
        return new A2ATaskView(task, task != null ? task.contextId : null, null, null, null, null, null, null);
    }

    static A2ATaskView of(A2ATaskState state) {
        return new A2ATaskView(state.toTask(), state.contextId, state.inputTokens, state.outputTokens,
                state.getAwaitCallId(), state.getAwaitTool(), state.getAwaitArguments(), state.errorMessage);
    }

    static A2ATaskView of(A2ATaskSnapshot snapshot) {
        return new A2ATaskView(snapshot.toTask(), snapshot.contextId, snapshot.inputTokens, snapshot.outputTokens,
                snapshot.awaitCallId, snapshot.awaitTool, snapshot.awaitArguments, snapshot.errorMessage);
    }
}
