package ai.core.memory.history;

import ai.core.llm.domain.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of ChatHistoryStore for testing.
 *
 * @author xander
 */
public class InMemoryChatHistoryStore implements ChatHistoryStore {

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(ChatSession session) {
        if (session == null || session.getId() == null) {
            return;
        }

        ChatSession existing = sessions.get(session.getId());
        if (existing != null) {
            // Update existing session
            existing.setTitle(session.getTitle());
            existing.setUpdatedAt(Instant.now());
            existing.setMetadata(session.getMetadata());
            if (session.getMessages() != null) {
                existing.addMessages(session.getMessages());
            }
        } else {
            // Clone and store
            ChatSession copy = ChatSession.builder()
                .id(session.getId())
                .userId(session.getUserId())
                .agentId(session.getAgentId())
                .title(session.getTitle())
                .messages(session.getMessages() != null ? new ArrayList<>(session.getMessages()) : new ArrayList<>())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .metadata(session.getMetadata())
                .build();
            sessions.put(session.getId(), copy);
        }
    }

    @Override
    public void appendMessages(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.addMessages(messages);
        }
    }

    @Override
    public Optional<ChatSession> findById(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<Message> getMessages(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        return session != null ? new ArrayList<>(session.getMessages()) : List.of();
    }

    @Override
    public List<ChatSession> listByUser(String userId) {
        return sessions.values().stream()
            .filter(s -> userId.equals(s.getUserId()))
            .sorted(Comparator.comparing(ChatSession::getUpdatedAt).reversed())
            .toList();
    }

    @Override
    public List<ChatSession> listByUser(String userId, int limit, int offset) {
        return sessions.values().stream()
            .filter(s -> userId.equals(s.getUserId()))
            .sorted(Comparator.comparing(ChatSession::getUpdatedAt).reversed())
            .skip(offset)
            .limit(limit)
            .toList();
    }

    @Override
    public void delete(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public void deleteByUser(String userId) {
        sessions.entrySet().removeIf(e -> userId.equals(e.getValue().getUserId()));
    }

    @Override
    public int countByUser(String userId) {
        return (int) sessions.values().stream()
            .filter(s -> userId.equals(s.getUserId()))
            .count();
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.setTitle(title);
            session.setUpdatedAt(Instant.now());
        }
    }

    /**
     * Clear all sessions (for testing).
     */
    public void clear() {
        sessions.clear();
    }

    /**
     * Get total session count (for testing).
     */
    public int size() {
        return sessions.size();
    }
}
