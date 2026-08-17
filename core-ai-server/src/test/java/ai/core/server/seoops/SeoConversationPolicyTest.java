package ai.core.server.seoops;

import ai.core.server.domain.ChatSession;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeoConversationPolicyTest {
    private final MongoCollection<ChatSession> sessions = mock();
    private final SeoConversationPolicy policy = new SeoConversationPolicy();

    @Test
    void acceptsOnlyOwnedActiveChatOrApiSessions() {
        policy.chatSessionCollection = sessions;
        var session = session("user-1", "api");
        when(sessions.get("session-1")).thenReturn(Optional.of(session));

        assertEquals("session-1", policy.requireOwnedChatSession("user-1", "session-1").id);

        session.userId = "other-user";
        assertThrows(NotFoundException.class,
            () -> policy.requireOwnedChatSession("user-1", "session-1"));
        session.userId = "user-1";
        session.source = "scheduled";
        assertThrows(NotFoundException.class,
            () -> policy.requireOwnedChatSession("user-1", "session-1"));
    }

    @Test
    void treatsBlankConversationAsAbsent() {
        policy.chatSessionCollection = sessions;

        assertNull(policy.requireOwnedChatSession("user-1", " "));
    }

    private ChatSession session(String userId, String source) {
        var session = new ChatSession();
        session.id = "session-1";
        session.userId = userId;
        session.source = source;
        return session;
    }
}
