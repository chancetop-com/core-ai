package ai.core.server.session;

import ai.core.server.domain.ChatMessage;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ChatMessageStore {
    Optional<ChatMessage> get(String id);

    /** @return false when the id or (sessionId, seq) already exists */
    boolean insertIfAbsent(ChatMessage message);

    /** highest seq of the session, or 0 when it has no messages */
    long latestSeq(String sessionId);

    List<ChatMessage> find(ChatMessageQuery query);

    long count(ChatMessageQuery query);
}
