package ai.core.server.trace.service;

import ai.core.server.domain.User;
import ai.core.server.rbac.RoleRegistry;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

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
    MongoCollection<User> userCollection;
    @Inject
    RoleRegistry roleRegistry;

    private volatile Set<String> cachedApiUserIds;
    private volatile long cachedApiUserIdsAt;

    public TraceVisibility resolve(String userId) {
        if (userId == null || userId.isBlank()) return TraceVisibility.of(Set.of());
        var user = userCollection.get(userId).orElse(null);
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
        var query = new Query();
        // both filters are index-backed; name ordering is applied in Java so a missing name index cannot break it
        query.filter = visibleUserIds != null
            ? Filters.in("_id", visibleUserIds)
            : Filters.in("user_type", "internal", USER_TYPE_API);
        query.limit = SCOPE_USERS_LIMIT;
        var users = userCollection.find(query);
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
            var query = new Query();
            query.filter = Filters.eq("user_type", USER_TYPE_API);
            query.limit = API_USER_LIMIT;
            var ids = new LinkedHashSet<String>();
            for (var user : userCollection.find(query)) {
                if (user.id != null && !user.id.isBlank()) ids.add(user.id);
            }
            current = Set.copyOf(ids);
            cachedApiUserIds = current;
            cachedApiUserIdsAt = System.currentTimeMillis();
            return current;
        }
    }
}
