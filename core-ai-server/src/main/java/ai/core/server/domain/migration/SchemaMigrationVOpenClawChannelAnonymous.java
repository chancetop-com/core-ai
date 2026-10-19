package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;
import org.bson.Document;

import java.util.List;

/**
 * OpenClaw gateways run inside platform sandboxes and call the channel sync endpoint without
 * user credentials. Requiring auth rejects every message before dispatch (the plain-HTTP
 * request cannot resolve a browser session), so existing openclaw channels are normalized to
 * anonymous; new ones are forced the same way when created or updated.
 *
 * @author stephen
 */
public class SchemaMigrationVOpenClawChannelAnonymous implements SchemaMigration {
    @Override
    public String version() {
        return "20261019001";
    }

    @Override
    public String description() {
        return "make openclaw channels anonymous";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.runCommand(new Document("update", "channels")
            .append("updates", List.of(new Document("q", new Document("channel_type", "openclaw"))
                .append("u", new Document("$set", new Document("require_auth", Boolean.FALSE)))
                .append("multi", Boolean.TRUE))));
    }
}
