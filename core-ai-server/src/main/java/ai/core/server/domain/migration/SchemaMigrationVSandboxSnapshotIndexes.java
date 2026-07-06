package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;
import static com.mongodb.client.model.Indexes.compoundIndex;
import static com.mongodb.client.model.Indexes.descending;

/**
 * @author xander
 */
public class SchemaMigrationVSandboxSnapshotIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260706001";
    }

    @Override
    public String description() {
        return "add sandbox_snapshots indexes for resume lookup and expiry cleanup";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("sandbox_snapshots", compoundIndex(ascending("session_id", "status"), descending("created_at")));
        mongo.createIndex("sandbox_snapshots", ascending("expires_at"));
    }
}
