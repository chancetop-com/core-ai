package ai.core.server.settings;

import ai.core.server.domain.SystemSettings;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.Optional;

/**
 * @author stephen
 */
public class MongoSystemSettingsStore implements SystemSettingsStore {
    @Inject
    MongoCollection<SystemSettings> collection;

    @Override
    public Optional<SystemSettings> get(String id) {
        return collection.get(id);
    }

    @Override
    public void insert(SystemSettings entity) {
        collection.insert(entity);
    }

    @Override
    public void replace(SystemSettings entity) {
        collection.replace(entity);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
