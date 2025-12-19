package ai.core.memory.longterm.store;

import ai.core.memory.longterm.RawConversationRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of RawConversationStore.
 * For development and testing purposes.
 *
 * @author xander
 */
public class InMemoryRawConversationStore implements RawConversationStore {

    private final Map<String, RawConversationRecord> records = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIndex = new ConcurrentHashMap<>();  // sessionId -> id

    @Override
    public void save(RawConversationRecord record) {
        records.put(record.getId(), record);
        if (record.getSessionId() != null) {
            sessionIndex.put(record.getSessionId(), record.getId());
        }
    }

    @Override
    public Optional<RawConversationRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public Optional<RawConversationRecord> findBySessionId(String sessionId) {
        String id = sessionIndex.get(sessionId);
        if (id != null) {
            return Optional.ofNullable(records.get(id));
        }
        return Optional.empty();
    }

    @Override
    public List<RawConversationRecord> findByUserId(String userId) {
        return records.values().stream()
            .filter(r -> userId.equals(r.getUserId()))
            .collect(Collectors.toList());
    }

    @Override
    public void delete(String id) {
        RawConversationRecord record = records.remove(id);
        if (record != null && record.getSessionId() != null) {
            sessionIndex.remove(record.getSessionId());
        }
    }

    @Override
    public void deleteByUserId(String userId) {
        List<String> toDelete = records.entrySet().stream()
            .filter(e -> userId.equals(e.getValue().getUserId()))
            .map(Map.Entry::getKey)
            .toList();

        for (String id : toDelete) {
            delete(id);
        }
    }

    @Override
    public int deleteExpired() {
        List<String> expired = records.entrySet().stream()
            .filter(e -> e.getValue().isExpired())
            .map(Map.Entry::getKey)
            .toList();

        for (String id : expired) {
            delete(id);
        }

        return expired.size();
    }

    @Override
    public int count() {
        return records.size();
    }
}
