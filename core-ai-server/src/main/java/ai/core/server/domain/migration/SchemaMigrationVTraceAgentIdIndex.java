package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVTraceAgentIdIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260609001";
    }

    @Override
    public String description() {
        return "create index on traces.agent_id for memory consolidation queries";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("traces", Indexes.ascending("agent_id"));
    }
}
