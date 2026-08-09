package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVArtifactListIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260808001";
    }

    @Override
    public String description() {
        return "create indexes backing the artifact list queries";
    }

    @Override
    public void migrate(Mongo mongo) {
        // file_records: user-scoped list sorted by created_at desc with skip/limit
        mongo.createIndex("file_records",
            Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("created_at")));
        // chat_sessions: resolve which session produced a set of file ids (artifacts.file_id is multikey)
        mongo.createIndex("chat_sessions",
            Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.ascending("artifacts.file_id")));
    }
}
