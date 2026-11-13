package ai.core.server.domain.migration;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author Xander
 */
public class SchemaMigrationVSessionShareIndexes implements SchemaMigration {
    @Override
    public String version() {
        return "20261113001";
    }

    @Override
    public String description() {
        return "create unique anonymous session share token index";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("session_shares", Indexes.ascending("token"), new IndexOptions().unique(true));
    }
}
