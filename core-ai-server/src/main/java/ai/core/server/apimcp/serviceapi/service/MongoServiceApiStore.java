package ai.core.server.apimcp.serviceapi.service;

import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoServiceApiStore implements ServiceApiStore {
    @Inject
    MongoCollection<ServiceApi> collection;

    @Override
    public List<ServiceApi> listAll() {
        return collection.find(Filters.empty());
    }

    @Override
    public Optional<ServiceApi> get(String id) {
        return collection.get(id);
    }

    @Override
    public Optional<ServiceApi> getByName(String name) {
        return collection.findOne(Filters.eq("name", name));
    }

    @Override
    public void insert(ServiceApi api) {
        collection.insert(api);
    }

    @Override
    public void replace(ServiceApi api) {
        collection.replace(api);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
