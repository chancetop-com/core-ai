package ai.core.memory.longterm;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Namespace for organizing memories hierarchically.
 * Allows flexible segmentation by organization, user, application, or any structure.
 *
 * <p>Examples:
 * <pre>{@code
 * Namespace.of("user-123")                    // Single level - by user
 * Namespace.of("org-abc", "user-123")         // Two levels - org/user
 * Namespace.of("app", "chat", "session-456")  // Three levels - app/chat/session
 * Namespace.global()                          // Global shared namespace
 * }</pre>
 *
 * @author xander
 */
public final class Namespace {

    private static final String DELIMITER = "/";
    private static final Namespace GLOBAL = new Namespace(List.of("__global__"));

    /**
     * Create a namespace from path parts.
     *
     * @param parts namespace path parts
     * @return namespace instance
     */
    public static Namespace of(String... parts) {
        if (parts == null || parts.length == 0) {
            return GLOBAL;
        }
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                throw new IllegalArgumentException("Namespace parts cannot be null or blank");
            }
        }
        return new Namespace(Arrays.asList(parts));
    }

    /**
     * Create a namespace from a list of parts.
     *
     * @param parts namespace path parts
     * @return namespace instance
     */
    public static Namespace of(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return GLOBAL;
        }
        return new Namespace(List.copyOf(parts));
    }

    /**
     * Get the global shared namespace.
     *
     * @return global namespace
     */
    public static Namespace global() {
        return GLOBAL;
    }

    /**
     * Create a user-scoped namespace.
     * Convenience method for common use case.
     *
     * @param userId user identifier
     * @return namespace scoped to user
     */
    public static Namespace forUser(String userId) {
        return of("user", userId);
    }

    /**
     * Create a session-scoped namespace.
     *
     * @param sessionId session identifier
     * @return namespace scoped to session
     */
    public static Namespace forSession(String sessionId) {
        return of("session", sessionId);
    }

    /**
     * Parse namespace from path string.
     *
     * @param path path string like "org/user/app"
     * @return namespace instance
     */
    public static Namespace fromPath(String path) {
        if (path == null || path.isBlank()) {
            return GLOBAL;
        }
        return of(path.split(DELIMITER));
    }

    private final List<String> parts;

    private Namespace(List<String> parts) {
        this.parts = parts;
    }

    /**
     * Get all namespace parts.
     *
     * @return list of parts
     */
    public List<String> getParts() {
        return parts;
    }

    /**
     * Get the first part (usually the top-level identifier).
     *
     * @return first part or null if empty
     */
    public String getFirst() {
        return parts.isEmpty() ? null : parts.getFirst();
    }

    /**
     * Get the last part (usually the most specific identifier).
     *
     * @return last part or null if empty
     */
    public String getLast() {
        return parts.isEmpty() ? null : parts.getLast();
    }

    /**
     * Get the depth of the namespace hierarchy.
     *
     * @return number of levels
     */
    public int depth() {
        return parts.size();
    }

    /**
     * Check if this is the global namespace.
     *
     * @return true if global
     */
    public boolean isGlobal() {
        return this == GLOBAL || parts.size() == 1 && "__global__".equals(parts.getFirst());
    }

    /**
     * Create a child namespace by appending a part.
     *
     * @param child child part to append
     * @return new namespace with appended part
     */
    public Namespace child(String child) {
        if (child == null || child.isBlank()) {
            throw new IllegalArgumentException("Child part cannot be null or blank");
        }
        var newParts = new java.util.ArrayList<>(parts);
        newParts.add(child);
        return new Namespace(newParts);
    }

    /**
     * Get parent namespace (all parts except last).
     *
     * @return parent namespace, or global if at root
     */
    public Namespace parent() {
        if (parts.size() <= 1) {
            return GLOBAL;
        }
        return new Namespace(parts.subList(0, parts.size() - 1));
    }

    /**
     * Check if this namespace starts with another namespace.
     *
     * @param prefix prefix to check
     * @return true if this namespace starts with prefix
     */
    public boolean startsWith(Namespace prefix) {
        if (prefix.parts.size() > this.parts.size()) {
            return false;
        }
        for (int i = 0; i < prefix.parts.size(); i++) {
            if (!prefix.parts.get(i).equals(this.parts.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Convert to path string representation.
     *
     * @return path string like "org/user/app"
     */
    public String toPath() {
        return String.join(DELIMITER, parts);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Namespace namespace = (Namespace) o;
        return Objects.equals(parts, namespace.parts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parts);
    }

    @Override
    public String toString() {
        return "Namespace(" + toPath() + ")";
    }
}
