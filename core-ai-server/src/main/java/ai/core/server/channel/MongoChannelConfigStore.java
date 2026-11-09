package ai.core.server.channel;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoChannelConfigStore extends ChannelConfigStore {
    @Inject
    MongoCollection<ChannelConfigView> collection;

    @Override
    protected Optional<ChannelConfigView> get(String channelId) {
        return collection.get(channelId);
    }

    @Override
    protected List<ChannelConfigView> list() {
        return collection.find(Filters.empty());
    }

    @Override
    protected void insert(ChannelConfigView config) {
        collection.insert(config);
    }

    @Override
    protected void replace(ChannelConfigView config) {
        collection.replace(config);
    }

    @Override
    protected void delete(String channelId) {
        collection.delete(channelId);
    }
}
