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

    void add(String content);

    void add(Message message);

    List<String> retrieve(String query, int topK);

    String buildContext();

    void clear();

    int size();

    default boolean isEmpty() {
        return size() == 0;
    }
}
