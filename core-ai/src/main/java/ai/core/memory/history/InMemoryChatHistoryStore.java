package ai.core.memory.history;

import ai.core.llm.domain.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author xander
 */
public class InMemoryChatHistoryStore implements ChatHistoryStore {

    private final ConcurrentMap<String, List<Message>> history = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> extractedIndex = new ConcurrentHashMap<>();

    @Override
    public void save(String sessionId, Message message) {
        if (message == null) {
            return;
        }
        String key = buildKey(sessionId);
        history.computeIfAbsent(key, k -> new ArrayList<>()).add(message);
    }

    @Override
    public void saveAll(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = buildKey(sessionId);
        history.computeIfAbsent(key, k -> new ArrayList<>()).addAll(messages);
    }

    @Override
    public List<Message> load(String sessionId) {
        String key = buildKey(sessionId);
        List<Message> messages = history.get(key);
        return messages != null ? new ArrayList<>(messages) : new ArrayList<>();
    }

    @Override
    public List<Message> loadRecent(String sessionId, int limit) {
        List<Message> all = load(sessionId);
        if (all.size() <= limit) {
            return all;
        }
        return new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    @Override
    public void markExtracted(String sessionId, int messageIndex) {
        String key = buildKey(sessionId);
        extractedIndex.put(key, messageIndex);
    }

    @Override
    public int getLastExtractedIndex(String sessionId) {
        String key = buildKey(sessionId);
        return extractedIndex.getOrDefault(key, -1);
    }

    @Override
    public List<Message> loadUnextracted(String sessionId) {
        List<Message> all = load(sessionId);
        int lastIndex = getLastExtractedIndex(sessionId);

        if (lastIndex < 0) {
            return all;
        }

        int fromIndex = lastIndex + 1;
        if (fromIndex >= all.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(all.subList(fromIndex, all.size()));
    }

    @Override
    public int count(String sessionId) {
        String key = buildKey(sessionId);
        List<Message> messages = history.get(key);
        return messages != null ? messages.size() : 0;
    }

    @Override
    public void clear(String sessionId) {
        String key = buildKey(sessionId);
        history.remove(key);
        extractedIndex.remove(key);
    }

    private String buildKey(String sessionId) {
        return sessionId != null ? sessionId : "_default_";
    }
}
