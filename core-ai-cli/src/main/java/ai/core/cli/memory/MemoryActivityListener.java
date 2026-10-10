package ai.core.cli.memory;

import ai.core.agent.Agent;

/**
 * Receives memory extraction activity (started / completed) tagged with the agent the run was
 * forked from, so a session client can map it to the owning conversation and surface what was
 * extracted. The app-server engine wraps reports as {@code memory} custom session events.
 *
 * @author stephen
 */
@FunctionalInterface
public interface MemoryActivityListener {
    void onMemoryActivity(Agent agent, MemoryExtractionReport report);
}
