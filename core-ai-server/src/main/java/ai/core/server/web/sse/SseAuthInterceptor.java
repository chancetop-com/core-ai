package ai.core.server.web.sse;

import ai.core.server.apiuser.PermissionService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.server.web.session.SessionIdentity;
import ai.core.sse.SseChannelInterceptor;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.exception.ForbiddenException;

/**
 * SSE auth interceptor that reuses the same {@link RequestAuthenticator} as the HTTP {@code AuthInterceptor}.
 * <p>
 * Registered via {@code PatchedServerSentEventConfig.intercept()}, runs before every SSE channel listener's
 * {@code onConnect} within a WebContext scope, so {@link AuthContext#userId(WebContext)} works downstream.
 * Authentication prefers the session identity (web login); bearer keys fall back. RBAC: SSE channels
 * do not go through {@code Invocation} (no method annotations), so the permission is resolved by path —
 * session channels require {@code chat.use}, notification channel requires {@code notification.view};
 * A2A stream is a protocol surface and stays open to authenticated users.
 *
 * @author core-ai
 */
public class SseAuthInterceptor implements SseChannelInterceptor {
    private final RequestAuthenticator requestAuthenticator;
    private final SessionIdentity sessionIdentity;
    private final PermissionService permissionService;

    public SseAuthInterceptor(RequestAuthenticator requestAuthenticator, SessionIdentity sessionIdentity, PermissionService permissionService) {
        this.requestAuthenticator = requestAuthenticator;
        this.sessionIdentity = sessionIdentity;
        this.permissionService = permissionService;
    }

    @Override
    public void onConnect(Request request, WebContext webContext) {
        var identity = sessionIdentity.getUserIdentityOrNull();
        if (identity != null) {
            webContext.put(AuthContext.USER_ID_KEY, identity.userId);
        } else {
            var apiKeyResult = requestAuthenticator.authenticateFromApiKeyRecord(request);
            if (apiKeyResult != null) {
                webContext.put(AuthContext.USER_ID_KEY, apiKeyResult.userId());
                webContext.put(AuthContext.KEY_ID_KEY, apiKeyResult.keyId());
            } else {
                var userId = requestAuthenticator.authenticate(request);
                webContext.put(AuthContext.USER_ID_KEY, userId);
            }
        }
        checkPermission(request.path(), AuthContext.userId(webContext));
    }

    private void checkPermission(String path, String userId) {
        String permission = null;
        if (path.startsWith("/api/sessions")) {
            permission = PermissionCodes.CHAT_USE;
        } else if (path.startsWith("/api/notifications")) {
            permission = PermissionCodes.NOTIFICATION_VIEW;
        }
        if (permission == null) return;
        var identity = sessionIdentity.getUserIdentityOrNull();
        var allowed = identity != null
            ? hasPermission(identity.permissions, permission)
            : permissionService.has(userId, permission);
        if (!allowed) {
            throw new ForbiddenException("permission required: " + permission);
        }
    }

    private boolean hasPermission(java.util.List<String> permissions, String permission) {
        if (permissions == null) return false;
        if (permissions.contains("*") || permissions.contains(permission)) return true;
        if (permission.endsWith(".view")) {
            return permissions.contains(permission.substring(0, permission.length() - ".view".length()) + "manage");
        }
        return false;
    }
}
