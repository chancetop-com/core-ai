package ai.core.server.channel.openclaw;

import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoOcgConfigStore extends OcgConfigStore {
    @Inject
    MongoCollection<OcgConfigView> collection;

    @Override
    protected Optional<OcgConfigView> get(String id) {
        return collection.get(id);
    }

    @Override
    protected Optional<OcgConfigView> findByChannelId(String channelId) {
        return collection.find(Filters.eq("channel_id", channelId)).stream().findFirst();
    }

    @Override
    protected List<OcgConfigView> list() {
        return collection.find(Filters.empty());
    }

    @Override
    protected void insert(OcgConfigView config) {
        collection.insert(config);
    }

    @Override
    protected void replace(OcgConfigView config) {
        collection.replace(config);
    }

    @Override
    protected void delete(String id) {
        collection.delete(id);
    }
}
