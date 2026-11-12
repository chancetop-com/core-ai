package ai.core.server;

import ai.core.server.comparison.AgentComparisonMigrationService;
import ai.core.server.comparison.AgentComparisonStore;
import ai.core.server.comparison.ComparisonVoteStore;
import ai.core.server.comparison.MongoAgentComparisonStore;
import ai.core.server.comparison.MongoComparisonVoteStore;
import ai.core.server.comparison.PgAgentComparisonStore;
import ai.core.server.comparison.PgComparisonVoteStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class AgentComparisonStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(AgentComparisonStore.class, bind(PgAgentComparisonStore.class));
            bind(ComparisonVoteStore.class, bind(PgComparisonVoteStore.class));
            var migration = bind(AgentComparisonMigrationService.class);
            onStartup(migration::migrate);
        } else {
            bind(AgentComparisonStore.class, bind(MongoAgentComparisonStore.class));
            bind(ComparisonVoteStore.class, bind(MongoComparisonVoteStore.class));
        }
    }
}
