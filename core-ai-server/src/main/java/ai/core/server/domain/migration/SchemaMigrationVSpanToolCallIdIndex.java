package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * @author stephen
 */
public class SchemaMigrationVSpanToolCallIdIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260903001";
    }

    @Override
    public String description() {
        return "index spans by (trace_id, tool_call_id) so gateway-synthesized tool spans dedupe idempotently";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("spans",
            Indexes.compoundIndex(Indexes.ascending("trace_id"), Indexes.ascending("tool_call_id")));
    }
}
