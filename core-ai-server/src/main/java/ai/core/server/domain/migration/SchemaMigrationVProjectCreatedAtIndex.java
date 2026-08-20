package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Backs the full project list of permission holders (project.view lets a user browse ALL
 * projects; the query is unordered on filter {} and sorted by created_at, which needs this
 * index to stay notablescan-safe).
 *
 * @author stephen
 */
public class SchemaMigrationVProjectCreatedAtIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260813006";
    }

    @Override
    public String description() {
        return "create projects.created_at index for the permission-holder full project list";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("projects", Indexes.ascending("created_at"));
    }
}
