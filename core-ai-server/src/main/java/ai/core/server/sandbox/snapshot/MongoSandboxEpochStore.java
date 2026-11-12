package ai.core.server.sandbox.snapshot;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.Optional;

/**
 * @author stephen
 */
public class MongoSandboxEpochStore implements SandboxEpochStore {
    public static MongoSandboxEpochStore of(MongoCollection<SandboxEpochDoc> collection) {
        var store = new MongoSandboxEpochStore();
        store.epochCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<SandboxEpochDoc> epochCollection;

    @Override
    public Optional<SandboxEpochDoc> get(String sessionId) {
        return epochCollection.get(sessionId);
    }

    @Override
    public boolean insertIfAbsent(SandboxEpochDoc doc) {
        try {
            epochCollection.insert(doc);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public boolean compareAndIncrement(String sessionId, long current, long next) {
        return epochCollection.update(
            Filters.and(Filters.eq("_id", sessionId), Filters.eq("epoch", current)),
            Updates.combine(Updates.set("epoch", next), Updates.set("updated_at", java.time.ZonedDateTime.now()))) == 1;
    }
}
