package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoGatewayProviderStore implements GatewayProviderStore {
    public static MongoGatewayProviderStore of(MongoCollection<GatewayProviderConfig> collection) {
        var store = new MongoGatewayProviderStore();
        store.collection = collection;
        return store;
    }

    @Inject
    MongoCollection<GatewayProviderConfig> collection;

    @Override
    public List<GatewayProviderConfig> listAll() {
        var query = new Query();
        query.sort = Sorts.ascending("name");
        return collection.find(query);
    }

    @Override
    public Optional<GatewayProviderConfig> get(String id) {
        return collection.get(id);
    }

    @Override
    public void insert(GatewayProviderConfig provider) {
        collection.insert(provider);
    }

    @Override
    public void replace(GatewayProviderConfig provider) {
        collection.replace(provider);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
