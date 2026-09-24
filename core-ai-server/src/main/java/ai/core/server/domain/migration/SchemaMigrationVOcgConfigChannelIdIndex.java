package ai.core.server.domain.migration;

import core.framework.mongo.Mongo;

import static com.mongodb.client.model.Indexes.ascending;

/**
 * {@code OcgConfigStore.loadByChannelId} is the lookup every proactive OpenClaw send and every
 * gateway callback does, and it filters on {@code channel_id} — a field no index covered. With
 * notablescan enabled the query fails with error 291, so scheduled runs, cost alerts and session
 * completion notifications bound to an openclaw channel were silently undeliverable.
 *
 * @author stephen
 */
public class SchemaMigrationVOcgConfigChannelIdIndex implements SchemaMigration {
    @Override
    public String version() {
        return "20260924001";
    }

    @Override
    public String description() {
        return "create ocg_configs channel_id index";
    }

    @Override
    public void migrate(Mongo mongo) {
        mongo.createIndex("ocg_configs", ascending("channel_id"));
    }
}
