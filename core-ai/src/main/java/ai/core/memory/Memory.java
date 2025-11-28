package ai.core.memory;

import ai.core.document.Document;
import ai.core.llm.domain.Message;

import java.util.List;

/**
 * Memory interface for agent memory systems.
 * Implementations handle different memory persistence strategies (short-term, medium-term, long-term).
 *
 * @author Xander
 */
public interface Memory {

    /**
     * Memory type identifier for context formatting.
     */
    String getType();

    /**
     * Save conversation messages to memory.
     * Implementation decides how to extract and persist relevant information.
     *
     * @param messages conversation messages to save
     */
    void save(List<Message> messages);

    /**
     * Retrieve relevant memory context for a given query.
     *
     * @param query the query to search for relevant memories
     * @return list of relevant documents from memory
     */
    List<Document> retrieve(String query);

    /**
     * Clear all stored memories.
     */
    void clear();

    /**
     * Check if memory contains any data.
     *
     * @return true if memory has stored content
     */
    default boolean isEmpty() {
        return retrieve("").isEmpty();
    }
}
