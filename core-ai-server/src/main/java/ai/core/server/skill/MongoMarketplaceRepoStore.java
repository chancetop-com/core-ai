package ai.core.server.skill;

import ai.core.server.domain.MarketplaceRepo;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoMarketplaceRepoStore implements MarketplaceRepoStore {
    @Inject
    MongoCollection<MarketplaceRepo> repoCollection;

    @Override
    public List<MarketplaceRepo> listAll() {
        var query = new Query();
        query.sort = Sorts.ascending("name");
        return repoCollection.find(query);
    }

    @Override
    public Optional<MarketplaceRepo> get(String id) {
        return repoCollection.get(id);
    }

    @Override
    public void insert(MarketplaceRepo repo) {
        repoCollection.insert(repo);
    }

    @Override
    public void delete(String id) {
        repoCollection.delete(id);
    }
}
