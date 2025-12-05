package ai.core.memory;

import ai.core.llm.domain.Message;

import java.util.List;

/**
 * Base interface for memory storage.
 * Provides a unified contract for different memory types (short-term, long-term, episodic).
 *
 * @author xander
 */
public interface MemoryStore {

    /**
     * Add content to memory.
     *
     * @param content the content to store
     */
    void add(String content);

    /**
     * Add a message to memory.
     *
     * @param message the message to store
     */
    void add(Message message);

    /**
     * Retrieve relevant content based on query.
     *
     * @param query the query string
     * @param topK  maximum number of results
     * @return list of relevant content strings
     */
    List<String> retrieve(String query, int topK);

    /**
     * Build context string for prompt injection.
     *
     * @return formatted context string
     */
    String buildContext();

    /**
     * Clear all stored content.
     */
    void clear();

    /**
     * Get the number of stored items.
     *
     * @return item count
     */
    int size();

    /**
     * Check if memory is empty.
     *
     * @return true if empty
     */
    default boolean isEmpty() {
        return size() == 0;
    }
}
