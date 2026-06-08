package ai.core.server.channel;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class ChannelConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelConfigStore.class);

    private final ConcurrentMap<String, ChannelConfigView> store = new ConcurrentHashMap<>();

    @Inject
    MongoCollection<ChannelConfigView> collection;

    public void loadAllFromDb() {
        var channels = collection.find(Filters.empty());
        for (var channel : channels) {
            store.put(channel.channelId, channel);
        }
        LOGGER.info("loaded {} channels from db", channels.size());
    }

    public void store(ChannelConfigView config) {
        store.put(config.channelId, config);
        try {
            var existing = collection.get(config.channelId).orElse(null);
            if (existing == null) {
                collection.insert(config);
            } else {
                collection.replace(config);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to persist channel to db, channelId={}", config.channelId, e);
        }
        LOGGER.info("stored channel config, channelId={}, type={}", config.channelId, config.channelType);
    }

    public ChannelConfigView load(String channelId) {
        return store.get(channelId);
    }

    public Map<String, ChannelConfigView> all() {
        return Map.copyOf(store);
    }

    public void remove(String channelId) {
        store.remove(channelId);
        try {
            collection.delete(channelId);
        } catch (Exception e) {
            LOGGER.warn("failed to delete channel from db, channelId={}", channelId, e);
        }
    }
}
