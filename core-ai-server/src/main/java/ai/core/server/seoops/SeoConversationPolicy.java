package ai.core.server.seoops;

import ai.core.server.domain.ChatSession;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;

import java.util.Set;

/**
 * @author xander
 */
public class SeoConversationPolicy {
    private static final Set<String> ALLOWED_SOURCES = Set.of("chat", "api");

    @Inject
    MongoCollection<ChatSession> chatSessionCollection;

    public ChatSession requireOwnedChatSession(String actorUserId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return null;
        var session = chatSessionCollection.get(conversationId.trim()).orElse(null);
        if (session == null || session.deletedAt != null || !actorUserId.equals(session.userId)
            || !ALLOWED_SOURCES.contains(session.source == null ? "chat" : session.source)) {
            throw new NotFoundException("conversation not found");
        }
        return session;
    }
}
