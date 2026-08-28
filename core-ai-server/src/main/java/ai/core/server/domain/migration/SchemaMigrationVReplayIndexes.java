package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Creates indexes for replay experiment collections.
 * <p>
 * Shared Mongo enforces notablescan: every list/sort path must be index-backed.
 * - (user_id, created_at): non-admin list
 * - (user_id, agent_id, created_at): non-admin list filtered by agent
 * - (created_at): admin unfiltered list (sort only)
 * - (origin, created_at): list origin filter + abandoned blank (playground) cleanup
 * - (experiment_id, created_at): runs of one experiment
 *
 * @author stephen
 */
public class SchemaMigrationVReplayIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260828001";
    }

    @Override
    public String description() {
        return "create indexes for replay_experiments and replay_runs";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("replay_experiments",
                Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("created_at")));
        mongo.createIndex("replay_experiments",
                Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.ascending("agent_id"), Indexes.descending("created_at")));
        mongo.createIndex("replay_experiments",
                Indexes.descending("created_at"));
        // blank-experiment cleanup: origin eq + created_at range keeps the delete scan index-backed
        mongo.createIndex("replay_experiments",
                Indexes.compoundIndex(Indexes.ascending("origin"), Indexes.ascending("created_at")));
        mongo.createIndex("replay_runs",
                Indexes.compoundIndex(Indexes.ascending("experiment_id"), Indexes.descending("created_at")));
    }
}
