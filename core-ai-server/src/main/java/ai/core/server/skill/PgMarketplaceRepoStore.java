package ai.core.server.skill;

import ai.core.server.domain.MarketplaceRepo;
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
public class PgMarketplaceRepoStore implements MarketplaceRepoStore {
    private static final String TABLE = "app.marketplace_repos";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static MarketplaceRepo toRepo(PgDocumentRow row) {
        return JsonUtil.fromJson(MarketplaceRepo.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<MarketplaceRepo> store() {
        return new PgDocumentStore<>(database, TABLE, MarketplaceRepo.class, repo -> repo.id);
    }

    @Override
    public List<MarketplaceRepo> listAll() {
        return database.select(SELECT + " ORDER BY data ->> ?", PgDocumentRow.class, "name")
            .stream().map(PgMarketplaceRepoStore::toRepo).toList();
    }

    @Override
    public Optional<MarketplaceRepo> get(String id) {
        return store().get(id);
    }

    @Override
    public void insert(MarketplaceRepo repo) {
        store().insert(repo);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}
