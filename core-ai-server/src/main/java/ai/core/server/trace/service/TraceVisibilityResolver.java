package ai.core.server.trace.service;

import ai.core.server.domain.User;
import ai.core.server.rbac.RoleRegistry;
import core.framework.inject.Inject;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves a caller's trace data scope from its role ({@code RoleRegistry}) and the API user set.
 * The API user set is cached for the same 5s window as role settings; the resolved scope is never
 * stored in the session, so a role change takes effect on the next request (<=5s).
 *
 * @author stephen
 */
public class TraceVisibilityResolver {
    private static final String USER_TYPE_API = "api";
    private static final Duration API_USER_CACHE_TTL = Duration.ofSeconds(5);
    private static final int API_USER_LIMIT = 5000;
    private static final int SCOPE_USERS_LIMIT = 500;

    private static String displayName(User user) {
        return user.name != null && !user.name.isBlank() ? user.name : user.id;
    }

    @Inject
    ai.core.server.user.UserStore userStore;
    @Inject
    RoleRegistry roleRegistry;

    private volatile Set<String> cachedApiUserIds;
    private volatile long cachedApiUserIdsAt;

    public TraceVisibility resolve(String userId) {
        if (userId == null || userId.isBlank()) return TraceVisibility.of(Set.of());
        var user = userStore.get(userId).orElse(null);
        if (user == null) return TraceVisibility.of(Set.of(userId));
        if (RoleRegistry.ROLE_ADMIN.equals(user.role)) return TraceVisibility.all();
        return switch (roleRegistry.traceScope(user.role)) {
            case ALL -> TraceVisibility.all();
            case API -> {
                var userIds = new LinkedHashSet<String>(apiUserIds());
                userIds.add(userId);
                yield TraceVisibility.of(userIds);
            }
            case SELF -> TraceVisibility.of(Set.of(userId));
        };
    }

    /**
     * Users the caller may filter traces by, for the Traces page user dropdown.
     * Unrestricted callers get the whole user directory (capped); scoped callers get exactly their set.
     */
    public List<User> visibleUsers(String userId) {
        var visibleUserIds = resolve(userId).scopedUserIdsOrNull();
        if (visibleUserIds != null && visibleUserIds.isEmpty()) return List.of();
        var users = visibleUserIds != null
            ? userStore.listByIds(visibleUserIds)
            : userStore.listInternalAndApiUsers();
        return users.stream()
            .sorted(Comparator.comparing(TraceVisibilityResolver::displayName, String.CASE_INSENSITIVE_ORDER))
            .limit(SCOPE_USERS_LIMIT)
            .toList();
    }

    private Set<String> apiUserIds() {
        var now = System.currentTimeMillis();
        var current = cachedApiUserIds;
        if (current != null && now - cachedApiUserIdsAt < API_USER_CACHE_TTL.toMillis()) return current;
        synchronized (this) {
            current = cachedApiUserIds;
            if (current != null && System.currentTimeMillis() - cachedApiUserIdsAt < API_USER_CACHE_TTL.toMillis()) return current;
            var ids = new LinkedHashSet<String>();
            for (var id : userStore.listUserIdsByType(USER_TYPE_API, API_USER_LIMIT)) {
                if (id != null && !id.isBlank()) ids.add(id);
            }
            current = Set.copyOf(ids);
            cachedApiUserIds = current;
            cachedApiUserIdsAt = System.currentTimeMillis();
            return current;
        }
    }
}
