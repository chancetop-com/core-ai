package ai.core.server.session;

import ai.core.server.domain.SessionFeedback;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

/**
 * @author stephen
 */
public class MongoSessionFeedbackStore implements SessionFeedbackStore {
    @Inject
    MongoCollection<SessionFeedback> collection;

    @Override
    public void insert(SessionFeedback feedback) {
        collection.insert(feedback);
    }

    @Override
    public boolean insertIfAbsent(SessionFeedback feedback) {
        try {
            collection.insert(feedback);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }
}
