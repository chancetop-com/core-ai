package ai.core.server.session;

import ai.core.server.domain.SessionNotice;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@link SessionNotice} rows: what the user was told, and whether they have written
 * back since.
 *
 * <p>Every write here is best-effort. A notice that could not be recorded still reached the user, and
 * an inbound message must never fail because a bookkeeping update did — the cost of a miss is that one
 * reply arrives without a pointer, not that the turn breaks.
 *
 * @author stephen
 */
public class SessionNoticeStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionNoticeStore.class);
    /** How many unanswered notices are ever shown: enough to cover a working day, not a backlog. */
    public static final int MAX_UNANSWERED = 3;

    @Inject
    MongoCollection<SessionNotice> sessionNoticeCollection;

    public void record(SessionNotice notice) {
        if (notice == null) return;
        try {
            sessionNoticeCollection.insert(notice);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record session notice, userId={}, channelId={}, sessionId={}",
                    notice.userId, notice.channelId, notice.sessionId, e);
        }
    }

    /** The user wrote on this channel and address: everything they were told before that is answered. */
    public void markAnswered(String userId, String channelId, String recipient) {
        if (isBlank(userId) || isBlank(channelId) || isBlank(recipient)) return;
        try {
            sessionNoticeCollection.update(filter(userId, channelId, recipient, true),
                    Updates.combine(Updates.set("answered", Boolean.TRUE), Updates.set("answered_at", ZonedDateTime.now())));
        } catch (RuntimeException e) {
            LOGGER.warn("failed to mark session notices answered, userId={}, channelId={}", userId, channelId, e);
        }
    }

    /** Newest first, bounded — an unanswered pile is not a queue to work through. */
    public List<SessionNotice> unanswered(String userId, String channelId, String recipient) {
        if (isBlank(userId) || isBlank(channelId) || isBlank(recipient)) return List.of();
        try {
            var query = new Query();
            query.filter = filter(userId, channelId, recipient, true);
            query.sort = Sorts.descending("created_at");
            query.limit = MAX_UNANSWERED;
            return sessionNoticeCollection.find(query);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read session notices, userId={}, channelId={}", userId, channelId, e);
            return List.of();
        }
    }

    private Bson filter(String userId, String channelId, String recipient, boolean unansweredOnly) {
        var filters = new ArrayList<Bson>(4);
        filters.add(Filters.eq("user_id", userId));
        filters.add(Filters.eq("channel_id", channelId));
        filters.add(Filters.eq("recipient", recipient));
        if (unansweredOnly) filters.add(Filters.eq("answered", Boolean.FALSE));
        return Filters.and(filters);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
