package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.List;

/**
 * Backfills user_type=internal for user documents created before the api-user feature
 * (they lack the field entirely, so user_type filters would hide them).
 * Uses the email index (exists:true) to satisfy notablescan on UAT/prod.
 *
 * @author stephen
 */
public class SchemaMigrationVBackfillUserType implements SchemaMigration {
    @Override
    public String version() {
        return "20260807001";
    }

    @Override
    public String description() {
        return "backfill users.user_type=internal for legacy documents without the field";
    }

    @Override
    public void migrate(Mongo mongo) {
        var filter = new Document("$and", List.of(
                new Document("email", new Document("$exists", Boolean.TRUE)),
                new Document("user_type", new Document("$exists", Boolean.FALSE))));
        var update = new Document("$set", new Document("user_type", "internal"));
        mongo.runCommand(new Document("update", "users")
                .append("updates", List.of(new Document("q", filter).append("u", update))));
    }
}
