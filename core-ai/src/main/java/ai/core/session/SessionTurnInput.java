package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.AttachedContent;
import ai.core.agent.PendingUserInputQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Owns the input window of one running turn. Messages offered here steer the current turn; messages
 * offered while the window is closed fall back to the session queue in the caller.
 *
 * @author stephen
 */
final class SessionTurnInput {
    private final Logger logger = LoggerFactory.getLogger(SessionTurnInput.class);
    private final SessionCommandQueue commandQueue;
    private final Agent agent;
    private final String sessionId;
    private volatile PendingUserInputQueue active;

    SessionTurnInput(SessionCommandQueue commandQueue, Agent agent, String sessionId) {
        this.commandQueue = commandQueue;
        this.agent = agent;
        this.sessionId = sessionId;
    }

    PendingUserInputQueue begin() {
        var input = new PendingUserInputQueue(SessionRuntimeLimits.current().maxQueuedMessages);
        active = input;
        agent.getExecutionContext().setPendingUserInput(input);
        return input;
    }

    boolean offer(String message, Map<String, Object> variables, List<AttachedContent> attachedContents) {
        var input = active;
        return input != null && input.offer(message, variables, attachedContents);
    }

    void end(PendingUserInputQueue input) {
        active = null;
        agent.getExecutionContext().setPendingUserInput(null);
        for (var message : input.closeAndDrain()) {
            if (!commandQueue.enqueueUserInput(message.message(), message.variables(), message.attachedContents())) {
                logger.warn("failed to requeue message accepted during the turn, sessionId={}", sessionId);
            }
        }
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
