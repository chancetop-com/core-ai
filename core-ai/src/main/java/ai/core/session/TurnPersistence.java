package ai.core.session;

import ai.core.agent.Agent;
import org.slf4j.Logger;

/**
 * Best-effort session save used when a turn ends without a normal completion. Extracted from
 * {@link InProcessAgentSession} (file-size budget) — the guards are the point: a provider-less or
 * empty session has nothing to write, and a save failure must never mask the error it was saving.
 *
 * @author stephen
 */
public final class TurnPersistence {
    static void save(Agent agent, String sessionId, Logger logger, String reason) {
        if (!agent.hasPersistenceProvider()) return;
        if (!agent.hasUserMessage()) return;
        try {
            agent.save(sessionId);
        } catch (RuntimeException e) {
            logger.warn("failed to persist session after {}, sessionId={}", reason, sessionId, e);
        }
    }

    private TurnPersistence() {
    }
}
