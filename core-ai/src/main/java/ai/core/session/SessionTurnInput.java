package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.AttachedContent;
import ai.core.agent.PendingUserInputQueue;
import ai.core.agent.PendingUserInputQueue.PendingUserInput;

import java.util.List;
import java.util.Map;

/**
 * Owns the input window of one running turn. Messages offered here steer the current turn; messages
 * offered while the window is closed fall back to the session queue in the caller.
 *
 * @author stephen
 */
final class SessionTurnInput {
    private final Agent agent;
    private volatile PendingUserInputQueue active;

    SessionTurnInput(Agent agent) {
        this.agent = agent;
    }

    PendingUserInputQueue begin() {
        var input = new PendingUserInputQueue(SessionRuntimeLimits.current().maxQueuedMessages);
        active = input;
        agent.getExecutionContext().setPendingUserInput(input);
        return input;
    }

    boolean isActive() {
        return active != null;
    }

    boolean offer(String message, Map<String, Object> variables, List<AttachedContent> attachedContents,
                  String turnId, Long steerSeq) {
        var input = active;
        return input != null && input.offer(message, variables, attachedContents, turnId, steerSeq);
    }

    List<PendingUserInput> end() {
        var input = active;
        active = null;
        agent.getExecutionContext().setPendingUserInput(null);
        return input == null ? List.of() : input.closeAndDrain();
    }

    boolean hasPending() {
        var input = active;
        return input != null && !input.isEmpty();
    }

    int pendingCount() {
        var input = active;
        return input == null ? 0 : input.size();
    }
}
