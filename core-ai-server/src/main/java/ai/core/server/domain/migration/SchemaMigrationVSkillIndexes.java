package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVSkillIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260320001";
    }

    @Override
    public String description() {
        return "create indexes for skills collection";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("skills", Indexes.ascending("qualified_name"), new IndexOptions().unique(true));
        mongo.createIndex("skills", Indexes.ascending("namespace"));
        mongo.createIndex("skills", Indexes.ascending("user_id"));
        mongo.createIndex("skills", Indexes.ascending("source_type"));
        mongo.createIndex("skills", Indexes.descending("created_at"));
    }
}
