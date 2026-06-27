package ai.core.server.channel.openclaw;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class OcgConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgConfigStore.class);

    private final ConcurrentMap<String, OcgConfigView> store = new ConcurrentHashMap<>();

    @Inject
    MongoCollection<OcgConfigView> collection;

    public void loadAllFromDb() {
        var configs = collection.find(Filters.empty());
        for (var config : configs) {
            store.put(config.id, config);
        }
        LOGGER.info("loaded {} OCG configs from db", configs.size());
    }

    public void store(OcgConfigView config) {
        store.put(config.id, config);
        try {
            var existing = collection.get(config.id).orElse(null);
            if (existing == null) {
                collection.insert(config);
            } else {
                collection.replace(config);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to persist OCG config to db, id={}", config.id, e);
        }
        LOGGER.info("stored OCG config, id={}, channelId={}", config.id, config.channelId);
    }

    public OcgConfigView load(String id) {
        return store.get(id);
    }

    public OcgConfigView loadByChannelId(String channelId) {
        return store.values().stream()
                .filter(config -> channelId.equals(config.channelId))
                .findFirst()
                .orElse(null);
    }

    public Map<String, OcgConfigView> all() {
        return Map.copyOf(store);
    }

    public List<OcgConfigView> allWithSandbox() {
        return store.values().stream()
                .filter(config -> config.sandboxId != null && !config.sandboxId.isBlank())
                .toList();
    }

    public void clearSandbox(String id) {
        var config = store.get(id);
        if (config == null) return;
        config.sandboxId = null;
        config.sandboxIp = null;
        try {
            collection.replace(config);
        } catch (Exception e) {
            LOGGER.warn("failed to clear OCG sandbox in db, id={}", id, e);
        }
    }

    public void remove(String id) {
        store.remove(id);
        try {
            collection.delete(id);
        } catch (Exception e) {
            LOGGER.warn("failed to delete OCG config from db, id={}", id, e);
        }
    }
}
