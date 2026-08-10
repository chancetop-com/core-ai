package ai.core.server.session;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Durable source of truth for session identity and authorization.
 */
public class SessionRegistry {
    private static final int DUPLICATE_KEY_CODE = 11000;
    private static final int TITLE_MAX_LENGTH = 40;

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    public ChatSession create(SessionRegistration registration) {
        var session = new ChatSession();
        session.id = registration.sessionId;
        session.userId = registration.userId;
        session.agentId = registration.agentId;
        session.source = normalizedSource(registration.source);
        session.scheduleId = registration.scheduleId;
        session.apiKeyId = registration.apiKeyId;
        session.messageCount = 0L;
        session.createdAt = ZonedDateTime.now();
        try {
            chatSessionCollection.insert(session);
            return session;
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            var existing = chatSessionCollection.get(registration.sessionId).orElse(null);
            if (sameIdentity(existing, registration)) return existing;
            throw new IllegalStateException("session id already registered with different identity, sessionId="
                    + registration.sessionId, e);
        }
    }

    public ChatSession requireAccessible(String sessionId, String callerUserId) {
        var session = chatSessionCollection.get(sessionId).orElse(null);
        if (session == null || session.deletedAt != null) {
            throw new NotFoundException("session not found, sessionId=" + sessionId);
        }
        if (callerUserId == null || callerUserId.isBlank() || !callerUserId.equals(session.userId)) {
            throw new ForbiddenException("session is unavailable");
        }
        return session;
    }

    public void recordUserMessage(String sessionId, String content) {
        var now = ZonedDateTime.now();
        var titled = chatSessionCollection.update(
                Filters.and(
                        Filters.eq("_id", sessionId),
                        Filters.or(Filters.exists("title", false), Filters.eq("title", null))),
                Updates.combine(
                        Updates.set("title", truncateTitle(content)),
                        Updates.set("last_message_at", now),
                        Updates.inc("message_count", 1L)));
        if (titled > 0) return;
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.combine(
                        Updates.set("last_message_at", now),
                        Updates.inc("message_count", 1L))));
    }

    public void addLoadedTools(String sessionId, List<ToolRef> toolRefs) {
        if (toolRefs == null || toolRefs.isEmpty()) return;
        requireUpdated(sessionId, chatSessionCollection.update(
                Filters.eq("_id", sessionId),
                Updates.addEachToSet("loaded_tools", toolRefs)));
    }

    private void requireUpdated(String sessionId, long updated) {
        if (updated == 0) {
            throw new IllegalStateException("session registry row missing, sessionId=" + sessionId);
        }
    }

    private boolean sameIdentity(ChatSession existing, SessionRegistration registration) {
        return existing != null
                && Objects.equals(existing.id, registration.sessionId)
                && Objects.equals(existing.userId, registration.userId)
                && Objects.equals(existing.agentId, registration.agentId)
                && Objects.equals(normalizedSource(existing.source), normalizedSource(registration.source))
                && Objects.equals(existing.scheduleId, registration.scheduleId)
                && Objects.equals(existing.apiKeyId, registration.apiKeyId);
    }

    private String normalizedSource(String source) {
        return source == null || source.isBlank() ? "chat" : source;
    }

    private String truncateTitle(String content) {
        if (content == null) return "";
        var cleaned = content.replaceAll("\\s+", " ").trim();
        return cleaned.length() > TITLE_MAX_LENGTH ? cleaned.substring(0, TITLE_MAX_LENGTH) : cleaned;
    }

    public record SessionRegistration(String sessionId, String userId, String agentId, String source,
                                      String scheduleId, String apiKeyId) { }
}
