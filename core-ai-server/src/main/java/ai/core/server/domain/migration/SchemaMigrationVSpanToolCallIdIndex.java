package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Do NOT register this in {@link SchemaMigrationManager}: on a large {@code spans} collection
 * ({@code createIndexes} waits for commit quorum on every voting member, well past the driver's
 * 15s socket timeout) the startup hook fails the whole app. This collection is 100 GB+ in UAT/prod,
 * so the index is created operationally and UAT records version 20260903001 manually.
 *
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
