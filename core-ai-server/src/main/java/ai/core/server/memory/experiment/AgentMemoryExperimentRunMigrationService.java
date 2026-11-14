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
public class AgentMemoryExperimentRunMigrationService extends PagedBackfill<AgentMemoryExperimentRun> {
    private static final String MARKER = "agent_memory_experiment_runs_v1";
    private static final String LOCK_KEY = "agent_memory_experiment_run_migration";
    private static final int PAGE_SIZE = 500;

    @Inject
    MongoCollection<AgentMemoryExperimentRun> runCollection;
    @Inject
    AgentMemoryExperimentStore experimentStore;

    @Override
    protected String name() {
        return "memory-experiment-run-migration";
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
    protected List<AgentMemoryExperimentRun> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        return runCollection.find(query);
    }

    @Override
    protected String key(AgentMemoryExperimentRun row) {
        return row.id;
    }

    @Override
    protected void write(List<AgentMemoryExperimentRun> page) {
        for (var run : page) {
            experimentStore.insertRun(run);
        }
    }

    @Override
    protected void writeOne(AgentMemoryExperimentRun row) {
        experimentStore.insertRun(row);
    }
}
