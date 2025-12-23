package ai.core.memory.history;

import ai.core.llm.domain.Message;

import java.util.List;
import java.util.Optional;

/**
 * Interface for storing and retrieving chat history.
 *
 * <p>Provides persistent storage for conversation messages across sessions,
 * enabling features like conversation replay, analytics, and audit trails.
 *
 * @author xander
 */
public interface ChatHistoryStore {

    /**
     * Save messages for a session.
     * If session exists, appends messages; otherwise creates new session.
     *
     * @param session the chat session with messages
     */
    void save(ChatSession session);

    /**
     * Append messages to an existing session.
     *
     * @param sessionId session identifier
     * @param messages  messages to append
     */
    void appendMessages(String sessionId, List<Message> messages);

    /**
     * Load a session by ID.
     *
     * @param sessionId session identifier
     * @return the session if found
     */
    Optional<ChatSession> findById(String sessionId);

    /**
     * Load messages for a session.
     *
     * @param sessionId session identifier
     * @return list of messages (empty if session not found)
     */
    List<Message> getMessages(String sessionId);

    /**
     * List all sessions for a user.
     *
     * @param userId user identifier
     * @return list of sessions (most recent first)
     */
    List<ChatSession> listByUser(String userId);

    /**
     * List sessions for a user with pagination.
     *
     * @param userId user identifier
     * @param limit  max number of sessions
     * @param offset offset for pagination
     * @return list of sessions
     */
    List<ChatSession> listByUser(String userId, int limit, int offset);

    /**
     * Delete a session and all its messages.
     *
     * @param sessionId session identifier
     */
    void delete(String sessionId);

    /**
     * Delete all sessions for a user.
     *
     * @param userId user identifier
     */
    void deleteByUser(String userId);

    /**
     * Count total sessions for a user.
     *
     * @param userId user identifier
     * @return session count
     */
    int countByUser(String userId);

    /**
     * Update session title.
     *
     * @param sessionId session identifier
     * @param title     new title
     */
    void updateTitle(String sessionId, String title);
}
