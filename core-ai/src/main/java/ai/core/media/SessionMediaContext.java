package ai.core.media;

import ai.core.agent.ExecutionContext;

/**
 * Reads the media store and its ownership scope off an execution context. The context carries them
 * as internal custom variables so a subagent inherits the parent session's store and key.
 *
 * @author core-ai
 */
public final class SessionMediaContext {
    public static SessionMediaStore store(ExecutionContext context) {
        if (context == null) return null;
        var value = context.getCustomVariable(SessionMediaStore.CONTEXT_KEY);
        return value instanceof SessionMediaStore store ? store : null;
    }

    public static SessionMediaKey key(ExecutionContext context) {
        if (context == null) return null;
        var value = context.getCustomVariable(SessionMediaStore.KEY_CONTEXT_KEY);
        if (value instanceof SessionMediaKey key) return key;
        var sessionId = context.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return null;
        return new SessionMediaKey(sessionId, context.getUserId());
    }

    private SessionMediaContext() {
    }
}
