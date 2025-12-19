package ai.core.memory.longterm.store;

import ai.core.memory.longterm.MemoryRecord;
import ai.core.memory.longterm.MemoryType;
import ai.core.memory.longterm.SearchFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of MemoryMetadataStore.
 * For development and testing purposes.
 *
 * @author xander
 */
public class InMemoryMetadataStore implements MemoryMetadataStore {

    private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(MemoryRecord record) {
        records.put(record.getId(), record);
    }

    @Override
    public void saveAll(List<MemoryRecord> recordList) {
        for (MemoryRecord record : recordList) {
            records.put(record.getId(), record);
        }
    }

    @Override
    public Optional<MemoryRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    @Override
    public List<MemoryRecord> findByIds(List<String> ids) {
        return ids.stream()
            .map(records::get)
            .filter(Objects::nonNull)
            .toList();
    }

    @Override
    public List<MemoryRecord> findByUserId(String userId) {
        return records.values().stream()
            .filter(r -> userId.equals(r.getUserId()))
            .toList();
    }

    @Override
    public void delete(String id) {
        records.remove(id);
    }

    @Override
    public void deleteByUserId(String userId) {
        records.entrySet().removeIf(e -> userId.equals(e.getValue().getUserId()));
    }

    @Override
    public List<MemoryRecord> findByUserIdWithFilter(String userId, SearchFilter filter) {
        return records.values().stream()
            .filter(r -> userId.equals(r.getUserId()))
            .filter(r -> filter == null || filter.matches(r))
            .toList();
    }

    @Override
    public List<MemoryRecord> findDecayed(String userId, double threshold) {
        return records.values().stream()
            .filter(r -> userId.equals(r.getUserId()))
            .filter(r -> r.getDecayFactor() < threshold)
            .toList();
    }

    @Override
    public List<MemoryRecord> findAllDecayed(double threshold) {
        return records.values().stream()
            .filter(r -> r.getDecayFactor() < threshold)
            .toList();
    }

    @Override
    public List<MemoryRecord> findAll() {
        return new ArrayList<>(records.values());
    }

    @Override
    public void recordAccess(String id) {
        MemoryRecord record = records.get(id);
        if (record != null) {
            record.incrementAccessCount();
        }
    }

    @Override
    public void recordAccess(List<String> ids) {
        for (String id : ids) {
            recordAccess(id);
        }
    }

    @Override
    public void updateDecayFactor(String id, double decayFactor) {
        MemoryRecord record = records.get(id);
        if (record != null) {
            record.setDecayFactor(decayFactor);
        }
    }

    @Override
    public void updateDecayFactors(List<String> ids, List<Double> decayFactors) {
        for (int i = 0; i < ids.size(); i++) {
            updateDecayFactor(ids.get(i), decayFactors.get(i));
        }
    }

    @Override
    public int count(String userId) {
        return (int) records.values().stream()
            .filter(r -> userId.equals(r.getUserId()))
            .count();
    }

    @Override
    public int countByType(String userId, MemoryType type) {
        return (int) records.values().stream()
            .filter(r -> userId.equals(r.getUserId()))
            .filter(r -> type == r.getType())
            .count();
    }

    @Override
    public int countAll() {
        return records.size();
    }
}
