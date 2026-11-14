package ai.core.server.memory.experiment;

import java.util.List;

/**
 * Storage for the memory experiment framework: one config per agent and the per-run records the
 * injection pipeline writes.
 *
 * @author stephen
 */
public interface AgentMemoryExperimentStore {
    AgentMemoryExperimentConfig getConfig(String agentId);

    void saveConfig(AgentMemoryExperimentConfig config);

    List<AgentMemoryExperimentConfig> findConfigs(int skip, int limit);

    long countConfigs();

    void deleteConfig(String agentId);

    void insertRun(AgentMemoryExperimentRun run);

    void updateRun(AgentMemoryExperimentRun run);

    /**
     * @return the newest run of the session, or null
     */
    AgentMemoryExperimentRun latestRunBySession(String sessionId);

    AgentMemoryExperimentRun runByRunId(String runId);

    List<AgentMemoryExperimentRun> runsByAgent(String agentId, int limit);

    List<AgentMemoryExperimentRun> listRuns(String agentId, int skip, int limit);

    long countRuns(String agentId);

    AgentMemoryExperimentRun getRun(String id);
}
