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
        // 20260706001 was claimed by another migration the same day (4-way collision found on dev);
        // this one never ran under the old version, so renumbering is safe.
        return "20260706009";
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
