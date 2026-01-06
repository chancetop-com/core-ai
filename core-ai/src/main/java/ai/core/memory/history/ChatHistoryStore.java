package ai.core.memory.history;

import ai.core.llm.domain.Message;

import java.util.List;

/**
 * @author xander
 */
public interface ChatHistoryStore {

    void save(String sessionId, Message message);

    void saveAll(String sessionId, List<Message> messages);

    List<Message> load(String sessionId);

    List<Message> loadRecent(String sessionId, int limit);

    void markExtracted(String sessionId, int messageIndex);

    int getLastExtractedIndex(String sessionId);

    List<Message> loadUnextracted(String sessionId);

    int count(String sessionId);

    void clear(String sessionId);
}
