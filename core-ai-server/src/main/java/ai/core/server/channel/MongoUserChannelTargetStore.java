package ai.core.server.channel;

import ai.core.server.domain.UserChannelTarget;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.Optional;

/**
 * @author stephen
 */
public class MongoUserChannelTargetStore extends UserChannelTargetStore {
    @Inject
    MongoCollection<UserChannelTarget> collection;

    @Override
    protected Optional<UserChannelTarget> get(String id) {
        return collection.get(id);
    }

    @Override
    protected void insert(UserChannelTarget target) {
        collection.insert(target);
    }

    @Override
    protected void replace(UserChannelTarget target) {
        collection.replace(target);
    }

    @Override
    public boolean insertIfAbsent(UserChannelTarget target) {
        try {
            collection.insert(target);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }
}
