package ai.core.agent;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;

import java.util.Locale;

/**
 * @author stephen
 */
final class AgentInterruptionHandler {

    static boolean shouldInjectMarker(CancelReason reason) {
        return reason == CancelReason.USER_CANCELLED
                || reason == CancelReason.REPLACED
                || reason == CancelReason.TIMEOUT;
    }

    static boolean isInterruptionMarker(Message msg) {
        if (msg.role != RoleType.USER) return false;
        var text = msg.getTextContent();
        return text != null && text.startsWith("<system-reminder>The previous");
    }

    static CancellationToken getCancellationToken(Agent agent) {
        if (agent.rootToken == null) {
            agent.rootToken = CancellationToken.create();
        }
        return agent.rootToken;
    }

    static void cancel(Agent agent) {
        boolean alreadyCancelled = getCancellationToken(agent).isCancelled();
        getCancellationToken(agent).cancel();
        if (!alreadyCancelled) {
            injectInterruptionMarker(agent);
            persistInterruptionMarkerIfExists(agent);
        }
    }

    static void throwIfCancelled(Agent agent) {
        agent.getExecutionContext().throwIfCancelled();
    }

    static void injectInterruptionMarker(Agent agent) {
        var token = getCancellationToken(agent);
        var reason = token.getReason();
        if (!shouldInjectMarker(reason)) return;

        var marker = reason == CancelReason.USER_CANCELLED
                ? "<system-reminder>The user interrupted the previous action. Do not continue what you were doing.</system-reminder>"
                : reason == CancelReason.REPLACED
                ? "<system-reminder>The previous turn was replaced by a new request. The results above may be incomplete.</system-reminder>"
                : "<system-reminder>The previous action was cancelled (reason: " + reason.name().toLowerCase(Locale.ENGLISH) + ").</system-reminder>";

        agent.addMessage(Message.of(RoleType.USER, marker));
    }

    static void persistInterruptionMarkerIfExists(Agent agent) {
        if (!agent.hasPersistenceProvider()) return;
        var sessionId = agent.getExecutionContext().getSessionId();
        if (sessionId != null) {
            agent.save(sessionId);
        }
    }

    private AgentInterruptionHandler() {
    }
}
