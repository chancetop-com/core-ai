package ai.core.session;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumption tombstones are generation-keyed: a re-run owes a notification, old ones stay dropped.
 *
 * @author stephen
 */
final class ConsumedTasks {
    private static final String ALL_GENERATIONS = "#*";

    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    void mark(String taskId) {
        keys.add(taskId + ALL_GENERATIONS);
    }

    void mark(String taskId, long generation) {
        keys.add(taskId + "#" + generation);
    }

    boolean isConsumed(String taskId) {
        var prefix = taskId + "#";
        return keys.stream().anyMatch(key -> key.startsWith(prefix));
    }

    boolean isConsumed(String taskId, Long generation) {
        if (keys.contains(taskId + ALL_GENERATIONS)) return true;
        if (generation == null) return isConsumed(taskId);
        return keys.contains(taskId + "#" + generation);
    }
}
