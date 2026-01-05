package ai.core.memory.longterm;

import java.util.Objects;

/**
 * Memory scope for user-level isolation.
 *
 * @author xander
 */
public final class MemoryScope {

    public static MemoryScope forUser(String userId) {
        return new MemoryScope(userId);
    }

    private final String userId;

    private MemoryScope(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public boolean matches(MemoryScope recordScope) {
        if (recordScope == null) {
            return false;
        }
        return userId != null && userId.equals(recordScope.userId);
    }

    public String toKey() {
        return userId != null ? "u:" + userId : "_global_";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemoryScope that = (MemoryScope) o;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }

    @Override
    public String toString() {
        return toKey();
    }
}
