package ai.core.session;

import ai.core.agent.Agent;
import ai.core.agent.AgentFootprint;

/**
 * Caches one session's content footprint. Refreshing at turn boundaries keeps the runtime stats job
 * from walking a message list that the turn thread is still mutating.
 *
 * @author stephen
 */
public class SessionFootprintTracker {
    private final Agent agent;
    private volatile AgentFootprint footprint = AgentFootprint.EMPTY;

    public SessionFootprintTracker(Agent agent) {
        this.agent = agent;
    }

    public AgentFootprint footprint() {
        return footprint;
    }

    public AgentFootprint refresh() {
        try {
            footprint = agent.footprint();
        } catch (RuntimeException e) {
            return footprint;
        }
        return footprint;
    }
}
