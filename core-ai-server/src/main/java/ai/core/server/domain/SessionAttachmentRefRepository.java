package ai.core.server.domain;

import core.framework.inject.Inject;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

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

    public List<SessionAttachmentRef> findSandboxAttachments(String sessionId, String userId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId),
                Filters.eq("kind", SessionAttachmentRef.KIND_SANDBOX));
        query.sort = Sorts.descending("created_at");
        return collection.find(query);
    }
}
