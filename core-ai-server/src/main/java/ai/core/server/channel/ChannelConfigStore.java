package ai.core.server.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public abstract class ChannelConfigStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelConfigStore.class);

    protected abstract Optional<ChannelConfigView> get(String channelId);

    protected abstract List<ChannelConfigView> list();

    protected abstract void insert(ChannelConfigView config);

    protected abstract void replace(ChannelConfigView config);

    protected abstract void delete(String channelId);

    public void store(ChannelConfigView config) {
        try {
            if (get(config.channelId).isEmpty()) {
                insert(config);
            } else {
                replace(config);
            }
        } catch (Exception e) {
            LOGGER.warn("failed to persist channel to db, channelId={}", config.channelId, e);
        }
        LOGGER.info("stored channel config, channelId={}, type={}", config.channelId, config.channelType);
    }

    public ChannelConfigView load(String channelId) {
        return get(channelId).orElse(null);
    }

    public Map<String, ChannelConfigView> all() {
        var result = new LinkedHashMap<String, ChannelConfigView>();
        for (var channel : list()) {
            result.put(channel.channelId, channel);
        }
        return result;
    }

    public void remove(String channelId) {
        try {
            delete(channelId);
        } catch (Exception e) {
            LOGGER.warn("failed to delete channel from db, channelId={}", channelId, e);
        }
    }
}
