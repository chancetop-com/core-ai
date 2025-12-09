package ai.core.memory.store;

import ai.core.memory.model.MemoryEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory implementation of KeyValueMemoryStore.
 * Suitable for development and testing, or single-instance deployments.
 *
 * @author xander
 */
public class InMemoryKVStore implements KeyValueMemoryStore {
    private final Map<String, MemoryEntry> store = new ConcurrentHashMap<>();

    @Override
    public void set(String key, MemoryEntry entry) {
        if (key != null && entry != null) {
            store.put(key, entry);
        }
    }

    @Override
    public Optional<MemoryEntry> get(String key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public List<MemoryEntry> getByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>(store.values());
        }
        return store.entrySet().stream()
            .filter(e -> e.getKey().startsWith(prefix))
            .map(Map.Entry::getValue)
            .toList();
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public boolean exists(String key) {
        return store.containsKey(key);
    }

    @Override
    public List<String> keys() {
        return new ArrayList<>(store.keySet());
    }

    @Override
    public List<String> keys(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return keys();
        }
        // Convert simple wildcard pattern to regex
        String regex = pattern.replace("*", ".*");
        Pattern compiled = Pattern.compile(regex);
        return store.keySet().stream()
            .filter(k -> compiled.matcher(k).matches())
            .toList();
    }

    @Override
    public void clear() {
        store.clear();
    }

    @Override
    public int size() {
        return store.size();
    }
}
