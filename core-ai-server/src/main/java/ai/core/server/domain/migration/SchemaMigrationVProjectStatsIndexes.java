package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Cached project cost stats: index for the dirty-scan of the stats refresh job, and backfill of
 * the dirty flag for existing active projects so the first refresh tick computes their snapshots.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectStatsIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260813007";
    }

    @Override
    public String description() {
        return "project stats cache: projects (status, stats_dirty) index + backfill stats_dirty for existing projects";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("projects",
            Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("stats_dirty")));
        // multi:true is required — the update command default only touches one matching document
        mongo.runCommand(new Document("update", "projects").append("updates", List.of(
            new Document("q", new Document("status", "active"))
                .append("u", new Document("$set", new Document("stats_dirty", Boolean.TRUE).append("updated_at", ZonedDateTime.now())))
                .append("multi", Boolean.TRUE))));
    }
}
