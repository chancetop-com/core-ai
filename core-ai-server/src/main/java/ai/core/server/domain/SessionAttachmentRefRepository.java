package ai.core.server.domain;

import core.framework.inject.Inject;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.regex.Pattern;

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

    /** Owned references whose id starts with {@code prefix}; lets a model that abbreviated {@code video_<uuid>} still hit the clip when the prefix is unique. */
    public List<SessionAttachmentRef> findOwnedByPrefix(String prefix, String sessionId, String userId) {
        return collection.find(Filters.and(
                Filters.regex("_id", Pattern.compile("^" + Pattern.quote(prefix))),
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId)));
    }

    /** Video references this session may watch, newest first; surfaced when a lookup misses so the model can pick a real id. */
    public List<SessionAttachmentRef> findOwnedVideos(String sessionId, String userId) {
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId),
                Filters.eq("kind", SessionAttachmentRef.KIND_VIDEO));
        query.sort = Sorts.descending("created_at");
        return collection.find(query);
    }

    /** Backfills the file record behind a reference minted before file ids were recorded. */
    public void attachFile(String referenceId, String fileId) {
        collection.update(Filters.eq("_id", referenceId), Updates.set("file_id", fileId));
    }

    /** The reference this session already holds for a blob, so re-listing a rendered clip does not mint a new id each time. */
    public SessionAttachmentRef findBySource(String sessionId, String userId, String container, String blobName) {
        return collection.find(Filters.and(
                Filters.eq("session_id", sessionId),
                Filters.eq("user_id", userId),
                Filters.eq("container", container),
                Filters.eq("blob_name", blobName))).stream().findFirst().orElse(null);
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
