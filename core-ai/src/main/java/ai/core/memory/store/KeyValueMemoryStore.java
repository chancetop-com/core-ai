package ai.core.memory.store;

import ai.core.memory.model.MemoryEntry;

import java.util.List;
import java.util.Optional;

/**
 * Key-Value store interface for fast exact-match memory lookup.
 * Used primarily for semantic memories with known subjects.
 *
 * @author xander
 */
public interface KeyValueMemoryStore {

    /**
     * Store a memory entry with the given key.
     *
     * @param key   the lookup key
     * @param entry the memory entry
     */
    void set(String key, MemoryEntry entry);

    /**
     * Get a memory entry by key.
     *
     * @param key the lookup key
     * @return optional memory entry
     */
    Optional<MemoryEntry> get(String key);

    /**
     * Get all memory entries with keys matching the prefix.
     *
     * @param prefix the key prefix
     * @return list of memory entries
     */
    List<MemoryEntry> getByPrefix(String prefix);

    /**
     * Delete a memory entry by key.
     *
     * @param key the lookup key
     */
    void delete(String key);

    /**
     * Check if a key exists.
     *
     * @param key the lookup key
     * @return true if exists
     */
    boolean exists(String key);

    /**
     * Get all keys.
     *
     * @return list of all keys
     */
    List<String> keys();

    /**
     * Get all keys matching the pattern.
     *
     * @param pattern the key pattern (supports * wildcard)
     * @return list of matching keys
     */
    List<String> keys(String pattern);

    /**
     * Clear all entries.
     */
    void clear();

    /**
     * Get total count of entries.
     *
     * @return count
     */
    int size();
}
