package ai.core.agent.subagent;

import java.util.regex.Pattern;

/**
 * Storage keys for subagent threads. Thread data and the per-session index live next to the session
 * under the same persistence provider, in a namespace the {@code ~sub~} infix keeps out of the
 * session listings. Task ids may contain characters that file-backed providers cannot store, so the
 * id part of a key is sanitized; the registry and the index always keep the raw id.
 *
 * @author stephen
 */
public final class SubagentPersistenceKeys {
    public static final String THREAD_INFIX = "~sub~";
    private static final String INDEX_SUFFIX = "~sub~_index";
    private static final int MAX_ID_LENGTH = 100;
    private static final Pattern UNSAFE_ID_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\s]");

    public static String index(String sessionId) {
        return sessionId + INDEX_SUFFIX;
    }

    public static String thread(String sessionId, String taskId) {
        return sessionId + THREAD_INFIX + sanitize(taskId);
    }

    static String sanitize(String taskId) {
        var safe = UNSAFE_ID_CHARS.matcher(taskId).replaceAll("-");
        return safe.length() > MAX_ID_LENGTH ? safe.substring(0, MAX_ID_LENGTH) : safe;
    }

    private SubagentPersistenceKeys() {
    }
}
