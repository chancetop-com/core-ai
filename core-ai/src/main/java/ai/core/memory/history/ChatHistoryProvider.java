package ai.core.memory.history;

import java.util.List;

/**
 * Provider for loading chat history for memory extraction.
 * User implements this interface to provide chat history from their storage.
 *
 * @author xander
 */
@FunctionalInterface
public interface ChatHistoryProvider {

    /**
     * Load all chat history for a user.
     *
     * @param userId the user identifier
     * @return list of chat records ordered by timestamp (oldest first)
     */
    List<ChatRecord> load(String userId);

    default List<ChatRecord> loadRecent(String userId, int limit) {
        List<ChatRecord> all = load(userId);
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }

    default int count(String userId) {
        return load(userId).size();
    }
}
