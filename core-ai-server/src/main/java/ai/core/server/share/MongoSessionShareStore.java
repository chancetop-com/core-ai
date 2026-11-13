package ai.core.server.share;

import ai.core.server.domain.SessionShare;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * @author Xander
 */
public class MongoSessionShareStore implements SessionShareStore {
    @Inject
    MongoCollection<SessionShare> collection;

    @Override
    public Optional<SessionShare> getBySessionId(String sessionId) {
        return collection.get(sessionId);
    }

    @Override
    public Optional<SessionShare> getByToken(String token) {
        return collection.findOne(Filters.eq("token", token));
    }

    @Override
    public boolean replace(SessionShare value, String expectedId) {
        try {
            if (expectedId == null) {
                collection.insert(value);
                return true;
            }
            return collection.update(Filters.and(Filters.eq("_id", value.sessionId), Filters.eq("share_id", expectedId)),
                    Updates.combine(
                            Updates.set("share_id", value.id),
                            Updates.set("owner_user_id", value.ownerUserId),
                            Updates.set("token", value.token),
                            Updates.set("title", value.title),
                            Updates.set("max_message_seq", value.maxMessageSeq),
                            Updates.set("message_count", value.messageCount),
                            Updates.set("created_at", value.createdAt),
                            Updates.set("expires_at", value.expiresAt),
                            Updates.set("revoked_at", value.revokedAt),
                            Updates.set("trace_ids", value.traceIds),
                            Updates.set("files", value.files))) > 0;
        } catch (MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public void revoke(String sessionId, String shareId, ZonedDateTime now) {
        collection.update(Filters.and(Filters.eq("_id", sessionId), Filters.eq("share_id", shareId), Filters.eq("revoked_at", null)),
                Updates.set("revoked_at", now));
    }
}
