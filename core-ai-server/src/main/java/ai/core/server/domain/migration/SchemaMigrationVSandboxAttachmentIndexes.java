package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

public class SchemaMigrationVSandboxAttachmentIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20260806002";
    }

    @Override
    public String description() {
        return "create sandbox attachment restore index";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("session_attachment_ref", Indexes.compoundIndex(
                Indexes.ascending("session_id"),
                Indexes.ascending("user_id"),
                Indexes.ascending("kind"),
                Indexes.descending("created_at")));
    }
}
