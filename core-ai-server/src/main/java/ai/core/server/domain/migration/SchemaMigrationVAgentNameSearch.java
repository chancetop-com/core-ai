package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.List;

public class SchemaMigrationVAgentNameSearch implements SchemaMigration {
    @Override
    public String version() {
        return "20260805002";
    }

    @Override
    public String description() {
        return "backfill normalized agent names and create picker indexes";
    }

    @Override
    public void migrate(Mongo mongo) {
        var normalized = new Document("$toLower", new Document("$trim",
            new Document("input", new Document("$ifNull", List.of("$name", "")))));
        var pipeline = List.of(new Document("$set", new Document("name_key", normalized)));
        mongo.runCommand(new Document("update", "agents")
            .append("updates", List.of(new Document("q", new Document())
                .append("u", pipeline)
                .append("multi", Boolean.TRUE))));
        mongo.createIndex("agents", Indexes.compoundIndex(
            Indexes.ascending("user_id"), Indexes.ascending("type"),
            Indexes.ascending("name_key"), Indexes.ascending("_id")));
        mongo.createIndex("agents", Indexes.compoundIndex(
            Indexes.ascending("status"), Indexes.ascending("type"),
            Indexes.ascending("name_key"), Indexes.ascending("_id")));
    }
}
