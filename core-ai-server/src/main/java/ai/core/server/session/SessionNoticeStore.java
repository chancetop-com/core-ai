package ai.core.server.session;

import ai.core.server.domain.SessionNotice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Reads and writes {@link SessionNotice} rows: what the user was told, and whether they have written
 * back since.
 *
 * <p>Every write here is best-effort. A notice that could not be recorded still reached the user, and
 * an inbound message must never fail because a bookkeeping update does — the cost of a miss is that one
 * reply arrives without a pointer, not that the turn breaks.
 *
 * @author stephen
 */
public abstract class SessionNoticeStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionNoticeStore.class);
    /** How many unanswered notices are ever shown: enough to cover a working day, not a backlog. */
    public static final int MAX_UNANSWERED = 3;

    protected abstract void insert(SessionNotice notice);

    protected abstract void updateAnswered(String userId, String channelId, String recipient, ZonedDateTime at);

    protected abstract List<SessionNotice> findUnanswered(String userId, String channelId, String recipient);

    public abstract boolean insertIfAbsent(SessionNotice notice);

    public void record(SessionNotice notice) {
        if (notice == null) return;
        try {
            insert(notice);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to record session notice, userId={}, channelId={}, sessionId={}",
                    notice.userId, notice.channelId, notice.sessionId, e);
        }
    }

    /** The user wrote on this channel and address: everything they were told before that is answered. */
    public void markAnswered(String userId, String channelId, String recipient) {
        if (isBlank(userId) || isBlank(channelId) || isBlank(recipient)) return;
        try {
            updateAnswered(userId, channelId, recipient, ZonedDateTime.now());
        } catch (RuntimeException e) {
            LOGGER.warn("failed to mark session notices answered, userId={}, channelId={}", userId, channelId, e);
        }
    }

    /** Newest first, bounded — an unanswered pile is not a queue to work through. */
    public List<SessionNotice> unanswered(String userId, String channelId, String recipient) {
        if (isBlank(userId) || isBlank(channelId) || isBlank(recipient)) return List.of();
        try {
            return findUnanswered(userId, channelId, recipient);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to read session notices, userId={}, channelId={}", userId, channelId, e);
            return List.of();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
