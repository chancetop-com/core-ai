package ai.core.trace.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVInitialize implements SchemaMigration {
    @Override
    public String version() {
        return "20260313001";
    }

    @Override
    public String description() {
        return "create indexes for traces, spans and prompt_templates";
    }

    @Override
    public void migrate(Mongo mongo) {
        // traces indexes
        mongo.createIndex("traces", Indexes.descending("created_at"));
        mongo.createIndex("traces", Indexes.ascending("trace_id"), new IndexOptions().unique(true));
        mongo.createIndex("traces", Indexes.ascending("session_id"));
        mongo.createIndex("traces", Indexes.ascending("user_id"));
        mongo.createIndex("traces", Indexes.ascending("status"));

        // spans indexes
        mongo.createIndex("spans", Indexes.ascending("trace_id"));
        mongo.createIndex("spans", Indexes.ascending("span_id"), new IndexOptions().unique(true));
        mongo.createIndex("spans", Indexes.ascending("parent_span_id"));
        mongo.createIndex("spans", Indexes.descending("created_at"));

        // prompt_templates indexes
        mongo.createIndex("prompt_templates", Indexes.ascending("name"));
        mongo.createIndex("prompt_templates", Indexes.ascending("status"));
        mongo.createIndex("prompt_templates", Indexes.descending("created_at"));
    }
}
