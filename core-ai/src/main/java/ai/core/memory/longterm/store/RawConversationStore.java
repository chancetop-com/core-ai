package ai.core.memory.longterm.store;

import ai.core.memory.longterm.RawConversationRecord;

import java.util.List;
import java.util.Optional;

/**
 * Interface for raw conversation storage.
 *
 * @author xander
 */
public interface RawConversationStore {

    void save(RawConversationRecord record);

    Optional<RawConversationRecord> findById(String id);

    Optional<RawConversationRecord> findBySessionId(String sessionId);

    List<RawConversationRecord> findByUserId(String userId);

    void delete(String id);

    void deleteByUserId(String userId);

    /**
     * Delete all expired records.
     *
     * @return number of deleted records
     */
    int deleteExpired();

    int count();
}
