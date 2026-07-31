package ai.core.server.domain;

import core.framework.inject.Inject;
import com.mongodb.client.model.Filters;
import core.framework.mongo.MongoCollection;

/**
 * @author stephen
 */
public class SessionAttachmentRefRepository {
    @Inject
    MongoCollection<SessionAttachmentRef> collection;

    public void insert(SessionAttachmentRef reference) {
        collection.insert(reference);
    }

    public SessionAttachmentRef findOwned(String referenceId, String sessionId, String userId) {
        return collection.find(Filters.and(
                Filters.eq("_id", referenceId),
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId))).stream().findFirst().orElse(null);
    }
}
