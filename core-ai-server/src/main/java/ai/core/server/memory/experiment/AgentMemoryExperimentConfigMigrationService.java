package ai.core.server.memory.experiment;

import ai.core.server.pg.PagedBackfill;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

/**
 * @author stephen
 */
public class AgentMemoryExperimentConfigMigrationService extends PagedBackfill<AgentMemoryExperimentConfig> {
    private static final String MARKER = "agent_memory_experiment_configs_v1";
    private static final String LOCK_KEY = "agent_memory_experiment_config_migration";
    private static final int PAGE_SIZE = 500;

    @Inject
    MongoCollection<AgentMemoryExperimentConfig> configCollection;
    @Inject
    AgentMemoryExperimentStore experimentStore;

    @Override
    protected String name() {
        return "memory-experiment-config-migration";
    }

    @Override
    protected String marker() {
        return MARKER;
    }

    @Override
    protected String lockKey() {
        return LOCK_KEY;
    }

    @Override
    protected List<AgentMemoryExperimentConfig> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        return configCollection.find(query);
    }

    @Override
    protected String key(AgentMemoryExperimentConfig row) {
        return row.id;
    }

    @Override
    protected void write(List<AgentMemoryExperimentConfig> page) {
        for (var config : page) {
            experimentStore.saveConfig(config);
        }
    }

    @Override
    protected void writeOne(AgentMemoryExperimentConfig row) {
        experimentStore.saveConfig(row);
    }
}
