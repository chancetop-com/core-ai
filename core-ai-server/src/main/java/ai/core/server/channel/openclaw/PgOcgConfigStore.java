package ai.core.server.channel.openclaw;

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
public class PgOcgConfigStore extends OcgConfigStore {
    private static final String TABLE = "app.ocg_configs";

    @Inject
    Database database;

    private PgDocumentStore<OcgConfigView> store() {
        return new PgDocumentStore<>(database, TABLE, OcgConfigView.class, config -> config.id);
    }

    @Override
    protected Optional<OcgConfigView> get(String id) {
        return store().get(id);
    }

    @Override
    protected Optional<OcgConfigView> findByChannelId(String channelId) {
        return database.selectOne("SELECT data::text AS data FROM " + TABLE + " WHERE data ->> ? = ?",
                PgDocumentRow.class, "channelId", channelId)
            .map(row -> JsonUtil.fromJson(OcgConfigView.class, row.data));
    }

    @Override
    protected List<OcgConfigView> list() {
        return database.select("SELECT data::text AS data FROM " + TABLE, PgDocumentRow.class)
            .stream().map(row -> JsonUtil.fromJson(OcgConfigView.class, row.data)).toList();
    }

    @Override
    protected void insert(OcgConfigView config) {
        store().insert(config);
    }

    @Override
    protected void replace(OcgConfigView config) {
        store().replace(config);
    }

    @Override
    protected void delete(String id) {
        store().delete(id);
    }
}
