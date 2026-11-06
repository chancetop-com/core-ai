package ai.core.server.memory;

import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deterministic memory governance for the server: removes expired trajectories and exact duplicate
 * rows. It never calls the LLM; semantic merge/conflict resolution belongs to the curator phase.
 */
public class AgentMemoryMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentMemoryMaintenanceService.class);

    @Inject
    AgentMemoryService agentMemoryService;

    public Report run(int trajectoryRetentionDays) {
        var agentIds = agentMemoryService.findAgentIdsWithMemories();
        int removedTrajectories = 0;
        int removedDuplicates = 0;
        for (var agentId : agentIds) {
            removedTrajectories += agentMemoryService.cleanupOldTrajectories(agentId, trajectoryRetentionDays);
            removedDuplicates += agentMemoryService.dedupeExactDuplicates(agentId);
        }
        LOGGER.info("agent memory maintenance completed: agents={}, removedTrajectories={}, removedDuplicates={}, retentionDays={}",
                agentIds.size(), removedTrajectories, removedDuplicates, trajectoryRetentionDays);
        return new Report(agentIds.size(), removedTrajectories, removedDuplicates);
    }

    public record Report(int agents, int removedTrajectories, int removedDuplicates) {
    }
}
