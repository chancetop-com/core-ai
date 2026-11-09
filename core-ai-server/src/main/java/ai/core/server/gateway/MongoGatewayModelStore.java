package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoGatewayModelStore implements GatewayModelStore {
    public static MongoGatewayModelStore of(MongoCollection<GatewayModelConfig> collection) {
        var store = new MongoGatewayModelStore();
        store.collection = collection;
        return store;
    }

    @Inject
    MongoCollection<GatewayModelConfig> collection;

    @Override
    public List<GatewayModelConfig> listAll() {
        var query = new Query();
        query.sort = Sorts.ascending("model_id");
        return collection.find(query);
    }

    @Override
    public Optional<GatewayModelConfig> get(String id) {
        return collection.get(id);
    }

    @Override
    public List<GatewayModelConfig> listByProviderId(String providerId) {
        return collection.find(Filters.eq("provider_id", providerId));
    }

    @Override
    public List<GatewayModelConfig> listByModelId(String modelId) {
        return collection.find(Filters.eq("model_id", modelId));
    }

    @Override
    public void insert(GatewayModelConfig model) {
        collection.insert(model);
    }

    @Override
    public void replace(GatewayModelConfig model) {
        collection.replace(model);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }

    @Override
    public void deleteByProviderId(String providerId) {
        collection.delete(Filters.eq("provider_id", providerId));
    }

    @Override
    public void clearDefault(String providerId) {
        collection.update(Filters.and(
                Filters.eq("provider_id", providerId),
                Filters.eq("is_default", Boolean.TRUE)),
            Updates.set("is_default", Boolean.FALSE));
    }

    @Override
    public void clearMessagesFallbackExcept(String keepId) {
        collection.update(Filters.and(
                Filters.eq("messages_fallback", Boolean.TRUE),
                Filters.ne("_id", keepId)),
            Updates.set("messages_fallback", Boolean.FALSE));
    }

    @Override
    public boolean existsEnabledModel(String modelId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("model_id", modelId),
                Filters.ne("enabled", Boolean.FALSE));
        query.limit = 1;
        return !collection.find(query).isEmpty();
    }

    @Override
    public boolean existsEnabledModelWithEndpoint(String modelId, String endpointType) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("model_id", modelId),
                Filters.ne("enabled", Boolean.FALSE),
                Filters.eq("endpoint_types", endpointType));
        query.limit = 1;
        return !collection.find(query).isEmpty();
    }
}
