package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;

import core.framework.mongo.Mongo;

/**
 * Project feature indexes (v1.3 derivation model): member-derived lookups on raw records,
 * the attribution table joins, the cost lens on traces and the analysis job scan.
 * Raw records carry NO project/subject fields in this model, so no binding indexes are needed.
 *
 * @author stephen
 */
public class SchemaMigrationVProjectIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260813004";
    }

    @Override
    public String description() {
        return "create indexes for project v1.3: members derivation, attribution table, trace cost lens and analysis scan";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("projects", Indexes.ascending("user_id"));                                          // my projects list
        mongo.createIndex("projects", Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("last_analyzed_at")));  // analysis job scan
        mongo.createIndex("project_subjects", Indexes.ascending("project_id"));                               // subject list / subject map
        mongo.createIndex("project_subject_attributions", Indexes.ascending("subject_id"));                   // attribution joins
        mongo.createIndex("chat_sessions", Indexes.compoundIndex(Indexes.ascending("agent_id"), Indexes.ascending("last_message_at")));  // members derivation
        mongo.createIndex("agent_runs", Indexes.compoundIndex(Indexes.ascending("agent_id"), Indexes.ascending("started_at")));          // members derivation
        mongo.createIndex("workflow_runs", Indexes.compoundIndex(Indexes.ascending("workflow_id"), Indexes.ascending("started_at")));    // members derivation
        mongo.createIndex("traces", Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.ascending("agent_id"), Indexes.ascending("started_at")));  // cost lens
    }
}
