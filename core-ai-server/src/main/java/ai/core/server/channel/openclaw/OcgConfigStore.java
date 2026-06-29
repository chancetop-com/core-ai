package ai.core.server.channel.openclaw;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class OcgConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(OcgConfigStore.class);

    @Inject
    MongoCollection<OcgConfigView> collection;

    public void store(OcgConfigView config) {
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
        return collection.get(id).orElse(null);
    }

    public OcgConfigView loadByChannelId(String channelId) {
        var configs = collection.find(Filters.eq("channelId", channelId));
        return configs.isEmpty() ? null : configs.get(0);
    }

    public Map<String, OcgConfigView> all() {
        var configs = collection.find(Filters.empty());
        var result = new LinkedHashMap<String, OcgConfigView>();
        for (var config : configs) {
            result.put(config.id, config);
        }
        return result;
    }

    public List<OcgConfigView> allWithSandbox() {
        var configs = collection.find(Filters.empty());
        return configs.stream()
                .filter(config -> config.sandboxId != null && !config.sandboxId.isBlank())
                .toList();
    }

    public void clearSandbox(String id) {
        var config = collection.get(id).orElse(null);
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
        try {
            collection.delete(id);
        } catch (Exception e) {
            LOGGER.warn("failed to delete OCG config from db, id={}", id, e);
        }
    }
}
