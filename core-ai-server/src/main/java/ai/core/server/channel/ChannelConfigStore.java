package ai.core.server.channel;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class ChannelConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelConfigStore.class);

    @Inject
    MongoCollection<ChannelConfigView> collection;

    public void store(ChannelConfigView config) {
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
        return collection.get(channelId).orElse(null);
    }

    public Map<String, ChannelConfigView> all() {
        var channels = collection.find(Filters.empty());
        var result = new LinkedHashMap<String, ChannelConfigView>();
        for (var channel : channels) {
            result.put(channel.channelId, channel);
        }
        return result;
    }

    public void remove(String channelId) {
        try {
            collection.delete(channelId);
        } catch (Exception e) {
            LOGGER.warn("failed to delete channel from db, channelId={}", channelId, e);
        }
    }
}
