package ai.core.server.web.session;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.User;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.UnauthorizedException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Session-backed user identity access, mirroring the fbr-project location-hub
 * session permission pattern. The identity (userId/name/role/permissions) is
 * stored as JSON in the core-ng session (Redis when {@code sys.redis.host} is
 * configured, local memory otherwise), so it is shared across pods and avoids
 * per-request DB lookups. Cached permissions expire after {@link #PERMISSIONS_TTL}
 * and are re-resolved from the role registry on next access.
 *
 * @author stephen
 */
public class SessionIdentity {
    public static final String USER_IDENTITY = "USER_IDENTITY";
    public static final String IDENTITY_CONTEXT_KEY = "auth.identity";
    private static final Duration PERMISSIONS_TTL = Duration.ofMinutes(5);

    @Inject
    WebContext webContext;
    @Inject
    PermissionService permissionService;
    @Inject
    MongoCollection<User> userCollection;

    /** Stores the resolved identity for the given internal user into the session. */
    public void setLoginSession(String userId) {
        webContext.request().session().set(USER_IDENTITY, JsonUtil.toJson(buildIdentity(userId)));
    }

    /** Reads the current identity; null when no session identity exists. */
    public UserIdentity getUserIdentityOrNull() {
        var cached = (UserIdentity) webContext.get(IDENTITY_CONTEXT_KEY);
        if (cached != null) return cached;
        var raw = webContext.request().session().get(USER_IDENTITY).orElse(null);
        if (raw == null) return null;
        var identity = JsonUtil.fromJson(UserIdentity.class, raw);
        webContext.put(IDENTITY_CONTEXT_KEY, identity);
        return refreshIfExpired(identity);
    }

    /** Reads the current identity or throws when the request has no session login. */
    public UserIdentity getUserIdentity() {
        var identity = getUserIdentityOrNull();
        if (identity == null) throw new UnauthorizedException("Please login first.");
        return identity;
    }

    public List<String> permissions() {
        return getUserIdentity().permissions;
    }

    /** Checks a permission against the session identity; false when no session login exists. */
    public boolean has(String permission) {
        var identity = getUserIdentityOrNull();
        if (identity == null) return false;
        return hasPermission(identity.permissions, permission);
    }

    /** Checks any of the given permissions (OR) against the session identity; false when no session login. */
    public boolean hasAny(String... permissions) {
        var identity = getUserIdentityOrNull();
        if (identity == null) return false;
        for (var permission : permissions) {
            if (hasPermission(identity.permissions, permission)) return true;
        }
        return false;
    }

    public void invalidate() {
        webContext.request().session().invalidate();
    }

    private boolean hasPermission(List<String> permissions, String permission) {
        if (permissions == null) return false;
        if (permissions.contains("*") || permissions.contains(permission)) return true;
        if (permission.endsWith(".view")) {
            return permissions.contains(permission.substring(0, permission.length() - ".view".length()) + "manage");
        }
        return false;
    }

    private UserIdentity refreshIfExpired(UserIdentity identity) {
        if (identity.expiredAt != null && identity.expiredAt.isBefore(ZonedDateTime.now())) {
            var refreshed = buildIdentity(identity.userId);
            webContext.request().session().set(USER_IDENTITY, JsonUtil.toJson(refreshed));
            webContext.put(IDENTITY_CONTEXT_KEY, refreshed);
            return refreshed;
        }
        return identity;
    }

    private UserIdentity buildIdentity(String userId) {
        var user = userCollection.get(userId)
                .orElseThrow(() -> new UnauthorizedException("user not found: " + userId));
        var identity = new UserIdentity();
        identity.userId = user.id;
        identity.name = user.name;
        identity.role = user.role;
        identity.permissions = permissionService.permissionsOf(userId);
        identity.expiredAt = ZonedDateTime.now().plus(PERMISSIONS_TTL);
        return identity;
    }
}
