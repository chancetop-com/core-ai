package ai.core.memory.history;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author xander
 */
public class InMemoryChatHistoryProvider implements ChatHistoryProvider {

    private final ConcurrentMap<String, List<ChatRecord>> history = new ConcurrentHashMap<>();

    @Override
    public List<ChatRecord> load(String userId) {
        String key = buildKey(userId);
        List<ChatRecord> records = history.get(key);
        return records != null ? new ArrayList<>(records) : new ArrayList<>();
    }

    public void addRecord(String userId, ChatRecord record) {
        if (record == null) {
            return;
        }
        String key = buildKey(userId);
        history.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
    }

    public void addRecords(String userId, List<ChatRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        String key = buildKey(userId);
        history.computeIfAbsent(key, k -> new ArrayList<>()).addAll(records);
    }

    public void clear(String userId) {
        String key = buildKey(userId);
        history.remove(key);
    }

    private String buildKey(String userId) {
        return userId != null ? userId : "_default_";
    }
}
