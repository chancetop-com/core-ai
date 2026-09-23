package ai.core.server.sandboxhub;

import ai.core.agent.Agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agents of in-flight {@code AgentRunner} runs, keyed by run id. A run has no session object, so the hub
 * cannot resolve it through {@code AgentSessionManager}; the runner publishes its agent here for the
 * duration of the run and the hub then serves that sandbox exactly as it serves a session's.
 *
 * @author stephen
 */
public class RunAgentRegistry {
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();

    public void register(String runId, Agent agent) {
        agents.put(runId, agent);
    }

    public void unregister(String runId) {
        agents.remove(runId);
    }

    /** Agent serving this id, or null when the id names no in-flight run on this replica. */
    public Agent agent(String runId) {
        return agents.get(runId);
    }
}
