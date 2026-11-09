package ai.core.server.channel;

import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgChannelConfigStore extends ChannelConfigStore {
    private static final String TABLE = "app.channels";

    @Inject
    Database database;

    private PgDocumentStore<ChannelConfigView> store() {
        return new PgDocumentStore<>(database, TABLE, ChannelConfigView.class, config -> config.channelId);
    }

    @Override
    protected Optional<ChannelConfigView> get(String channelId) {
        return store().get(channelId);
    }

    @Override
    protected List<ChannelConfigView> list() {
        return database.select("SELECT data::text AS data FROM " + TABLE, PgDocumentRow.class)
            .stream().map(row -> JsonUtil.fromJson(ChannelConfigView.class, row.data)).toList();
    }

    @Override
    protected void insert(ChannelConfigView config) {
        store().insert(config);
    }

    @Override
    protected void replace(ChannelConfigView config) {
        store().replace(config);
    }

    @Override
    protected void delete(String channelId) {
        store().delete(channelId);
    }
}
