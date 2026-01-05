package ai.core.memory.longterm;

import java.util.Objects;

/**
 * @author xander
 */
//todo 没有隔离，如何查Session 过去的会话内容
public final class MemoryScope {

    public static MemoryScope of(String userId) {
        return new MemoryScope(userId, null, null);
    }

    public static MemoryScope of(String userId, String sessionId) {
        return new MemoryScope(userId, sessionId, null);
    }

    public static MemoryScope of(String userId, String sessionId, String agentName) {
        return new MemoryScope(userId, sessionId, agentName);
    }

    public static MemoryScope forUser(String userId) {
        return new MemoryScope(userId, null, null);
    }

    public static MemoryScope forSession(String userId, String sessionId) {
        return new MemoryScope(userId, sessionId, null);
    }

    public static MemoryScope forAgent(String userId, String agentName) {
        return new MemoryScope(userId, null, agentName);
    }

    private final String userId;
    private final String sessionId;
    private final String agentName;

    private MemoryScope(String userId, String sessionId, String agentName) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.agentName = agentName;
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getAgentName() {
        return agentName;
    }

    public boolean hasUserId() {
        return userId != null && !userId.isEmpty();
    }

    public boolean hasSessionId() {
        return sessionId != null && !sessionId.isEmpty();
    }

    public boolean hasAgentName() {
        return agentName != null && !agentName.isEmpty();
    }

    public boolean matches(MemoryScope recordScope) {
        if (recordScope == null) {
            return false;
        }
        if (userId != null && !userId.equals(recordScope.userId)) {
            return false;
        }
        if (sessionId != null && !sessionId.equals(recordScope.sessionId)) {
            return false;
        }
        return agentName == null || agentName.equals(recordScope.agentName);
    }

    public String toKey() {
        StringBuilder sb = new StringBuilder();
        if (userId != null) {
            sb.append("u:").append(userId);
        }
        if (sessionId != null) {
            if (!sb.isEmpty()) sb.append('/');
            sb.append("s:").append(sessionId);
        }
        if (agentName != null) {
            if (!sb.isEmpty()) sb.append('/');
            sb.append("a:").append(agentName);
        }
        return sb.isEmpty() ? "_global_" : sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemoryScope that = (MemoryScope) o;
        return Objects.equals(userId, that.userId)
            && Objects.equals(sessionId, that.sessionId)
            && Objects.equals(agentName, that.agentName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, sessionId, agentName);
    }

    @Override
    public String toString() {
        return toKey();
    }
}
