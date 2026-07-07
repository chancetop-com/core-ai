package ai.core.server.domain.migration;

import com.mongodb.client.model.Indexes;
import core.framework.mongo.Mongo;

/**
 * Create compound indexes on traces for memory consolidation queries.
 * <p>
 * The {@code collectAgentIds()} query filters by {@code status=COMPLETED},
 * {@code agent_id} exists/non-null/non-empty, and {@code started_at <= cutoff}.
 * The compound index {@code (status, agent_id, started_at)} covers this query.
 * <p>
 * The per-agent {@code processAgent()} query filters by
 * {@code agent_id=<id>, status=COMPLETED, started_at > since, started_at <= cutoff}.
 * The compound index {@code (agent_id, status, started_at)} covers this query.
 * <p>
 * Without these compound indexes, MongoDB can only use a single-field index
 * and must scan documents for the remaining filter conditions, causing
 * 400ms+ query times even for small result sets.
 *
 * @author core-ai-cli
 */
public class SchemaMigrationVTraceMemoryConsolidationIndexes implements SchemaMigration {
    @Override
    public String version() {
        // 20260706001 is claimed by SchemaMigrationVGatewayModelModelIdIndex (same-day collision)
        return "20260706004";
    }

    @Override
    public String description() {
        return "create compound indexes on traces for memory consolidation queries";
    }

    @Override
    public void migrate(Mongo mongo) {
        // Covers collectAgentIds(): status=COMPLETED, agent_id exists/non-null/non-empty, started_at <= cutoff
        mongo.createIndex("traces", Indexes.compoundIndex(
                Indexes.ascending("status"),
                Indexes.ascending("agent_id"),
                Indexes.ascending("started_at")
        ));

        // Covers processAgent(): agent_id=<id>, status=COMPLETED, started_at range
        mongo.createIndex("traces", Indexes.compoundIndex(
                Indexes.ascending("agent_id"),
                Indexes.ascending("status"),
                Indexes.ascending("started_at")
        ));
    }
}
