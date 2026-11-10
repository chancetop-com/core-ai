package ai.core.server.domain;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.regex.Pattern;

/**
 * @author stephen
 */
public class MongoSessionAttachmentRefRepository implements SessionAttachmentRefRepository {
    @Inject
    MongoCollection<SessionAttachmentRef> collection;

    @Override
    public void insert(SessionAttachmentRef reference) {
        collection.insert(reference);
    }

    @Override
    public boolean insertIfAbsent(SessionAttachmentRef reference) {
        try {
            collection.insert(reference);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public SessionAttachmentRef findOwned(String referenceId, String sessionId, String userId) {
        return collection.find(Filters.and(
                Filters.eq("_id", referenceId),
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId))).stream().findFirst().orElse(null);
    }

    @Override
    public List<SessionAttachmentRef> findOwnedByPrefix(String prefix, String sessionId, String userId) {
        return collection.find(Filters.and(
                Filters.regex("_id", Pattern.compile("^" + Pattern.quote(prefix))),
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId)));
    }

    @Override
    public List<SessionAttachmentRef> findOwnedVideos(String sessionId, String userId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId),
                Filters.eq("kind", SessionAttachmentRef.KIND_VIDEO));
        query.sort = Sorts.descending("created_at");
        return collection.find(query);
    }

    @Override
    public void attachFile(String referenceId, String fileId) {
        collection.update(Filters.eq("_id", referenceId), Updates.set("file_id", fileId));
    }

    @Override
    public SessionAttachmentRef findBySource(String sessionId, String userId, String container, String blobName) {
        return collection.find(Filters.and(
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId),
                Filters.eq("container", container),
                Filters.eq("blob_name", blobName))).stream().findFirst().orElse(null);
    }

    @Override
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
