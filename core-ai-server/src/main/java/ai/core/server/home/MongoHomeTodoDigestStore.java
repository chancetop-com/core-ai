package ai.core.server.home;

import ai.core.server.domain.HomeTodoDigestRun;
import com.mongodb.MongoWriteException;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.Optional;

/**
 * @author core-ai
 */
public class MongoHomeTodoDigestStore implements HomeTodoDigestStore {
    @Inject
    MongoCollection<HomeTodoDigestRun> collection;

    @Override
    public Optional<HomeTodoDigestRun> get(String id) {
        return collection.get(id);
    }

    @Override
    public boolean insertIfAbsent(HomeTodoDigestRun run) {
        try {
            collection.insert(run);
            return true;
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void replace(HomeTodoDigestRun run) {
        collection.replace(run);
    }
}
