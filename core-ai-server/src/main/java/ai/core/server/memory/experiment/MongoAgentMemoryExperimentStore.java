package ai.core.server.memory.experiment;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

/**
 * @author stephen
 */
public class MongoAgentMemoryExperimentStore implements AgentMemoryExperimentStore {
    @Inject
    MongoCollection<AgentMemoryExperimentConfig> configCollection;
    @Inject
    MongoCollection<AgentMemoryExperimentRun> runCollection;

    @Override
    public AgentMemoryExperimentConfig getConfig(String agentId) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.limit = 1;
        var results = configCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public void saveConfig(AgentMemoryExperimentConfig config) {
        if (configCollection.get(config.id).isPresent()) {
            configCollection.replace(config);
        } else {
            configCollection.insert(config);
        }
    }

    @Override
    public List<AgentMemoryExperimentConfig> findConfigs(int skip, int limit) {
        var query = new Query();
        query.sort = Sorts.ascending("agent_id");
        query.skip = skip;
        query.limit = limit;
        return configCollection.find(query);
    }

    @Override
    public long countConfigs() {
        return configCollection.count(Filters.empty());
    }

    @Override
    public void deleteConfig(String agentId) {
        configCollection.delete(Filters.eq("agent_id", agentId));
    }

    @Override
    public void insertRun(AgentMemoryExperimentRun run) {
        runCollection.insert(run);
    }

    @Override
    public void updateRun(AgentMemoryExperimentRun run) {
        runCollection.replace(run);
    }

    @Override
    public AgentMemoryExperimentRun latestRunBySession(String sessionId) {
        var query = new Query();
        query.filter = Filters.eq("session_id", sessionId);
        query.sort = Sorts.descending("created_at");
        query.limit = 1;
        var runs = runCollection.find(query);
        return runs.isEmpty() ? null : runs.getFirst();
    }

    @Override
    public AgentMemoryExperimentRun runByRunId(String runId) {
        var query = new Query();
        query.filter = Filters.eq("run_id", runId);
        query.limit = 1;
        var runs = runCollection.find(query);
        return runs.isEmpty() ? null : runs.getFirst();
    }

    @Override
    public List<AgentMemoryExperimentRun> runsByAgent(String agentId, int limit) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.descending("created_at");
        query.limit = limit;
        return runCollection.find(query);
    }

    @Override
    public List<AgentMemoryExperimentRun> listRuns(String agentId, int skip, int limit) {
        var query = new Query();
        if (agentId != null && !agentId.isBlank()) {
            query.filter = Filters.eq("agent_id", agentId);
        }
        query.sort = Sorts.descending("created_at");
        query.skip = skip;
        query.limit = limit;
        return runCollection.find(query);
    }

    @Override
    public long countRuns(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return runCollection.count(Filters.eq("agent_id", agentId));
        }
        return runCollection.count(Filters.empty());
    }

    @Override
    public AgentMemoryExperimentRun getRun(String id) {
        var query = new Query();
        query.filter = Filters.eq("_id", id);
        query.limit = 1;
        var results = runCollection.find(query);
        return results.isEmpty() ? null : results.getFirst();
    }
}
