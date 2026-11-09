package ai.core.server.trigger;

import ai.core.server.trigger.domain.Trigger;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoTriggerStore implements TriggerStore {
    @Inject
    MongoCollection<Trigger> collection;

    @Override
    public List<Trigger> list(String type) {
        return type == null || type.isBlank() ? collection.find(Filters.empty()) : collection.find(Filters.eq("type", type));
    }

    @Override
    public Optional<Trigger> get(String id) {
        return collection.get(id);
    }

    @Override
    public void insert(Trigger trigger) {
        collection.insert(trigger);
    }

    @Override
    public void replace(Trigger trigger) {
        collection.replace(trigger);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
