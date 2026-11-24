package ai.core.server.runtime;

import ai.core.server.web.auth.AuthContext;
import core.framework.http.HTTPMethod;
import core.framework.web.Invocation;
import core.framework.web.Interceptor;
import core.framework.web.Response;
import core.framework.web.WebContext;

import java.util.List;

/**
 * Rejects work-creating requests while this pod is saturated. The guarded set is a route table rather
 * than an exact path list: a new write entry point must be added to {@link #WORK_ROUTES} (or be
 * explicitly ignored by not matching), and {@code AdmissionInterceptorTest} enumerates the known chat
 * entry points so a forgotten route fails the build instead of silently bypassing admission.
 *
 * @author stephen
 */
public class AdmissionInterceptor implements Interceptor {
    // Specific patterns first; :param matches exactly one non-empty path segment and /** a prefix.
    static final List<WorkRoute> WORK_ROUTES = List.of(
            new WorkRoute(HTTPMethod.POST, "/api/sessions", "session_create"),
            new WorkRoute(HTTPMethod.POST, "/api/sessions/messages/stream", "chat_turn"),
            new WorkRoute(HTTPMethod.POST, "/api/sessions/:sessionId/messages", "chat_turn"),
            new WorkRoute(HTTPMethod.PUT, "/api/sessions/events", "chat_turn"),
            new WorkRoute(HTTPMethod.POST, "/api/runs", "run"),
            new WorkRoute(HTTPMethod.POST, "/api/channels/**", "channel"),
            new WorkRoute(HTTPMethod.POST, "/api/gateway/v1/**", "gateway"));

    /** @return the work scope the request belongs to, or null when it creates no work. */
    static WorkRoute workRoute(HTTPMethod method, String path) {
        if (path == null || method == null) return null;
        for (var route : WORK_ROUTES) {
            if (route.method() != method) continue;
            if (matches(route.pattern(), path)) return route;
        }
        return null;
    }

    static boolean guarded(HTTPMethod method, String path) {
        return workRoute(method, path) != null;
    }

    private static boolean matches(String pattern, String path) {
        if (pattern.endsWith("/**")) return path.startsWith(pattern.substring(0, pattern.length() - 3));
        var expected = pattern.split("/");
        var actual = path.split("/");
        if (expected.length != actual.length) return false;
        for (var i = 0; i < expected.length; i++) {
            if (expected[i].startsWith(":") && !actual[i].isEmpty()) continue;
            if (!expected[i].equals(actual[i])) return false;
        }
        return true;
    }

    private final RuntimeCapacityGuard guard;

    public AdmissionInterceptor(RuntimeCapacityGuard guard) {
        this.guard = guard;
    }

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var route = workRoute(request.method(), request.path());
        if (route == null) return invocation.proceed();

        var scope = guard.saturatedScope();
        if (scope == null) scope = saturatedUserScope(invocation.context(), route);
        if (scope == null) return invocation.proceed();

        guard.recordSaturated(scope);
        if (!guard.limits().admissionEnabled) return invocation.proceed();

        guard.recordRejected(scope);
        throw new CapacityExceededException(scope, guard.retryAfterSeconds(),
                "platform is at capacity (" + scope + "), retry later");
    }

    private String saturatedUserScope(WebContext context, WorkRoute route) {
        if (!"chat_turn".equals(route.scope())) return null;
        return guard.saturatedUserScope(AuthContext.userId(context));
    }

    record WorkRoute(HTTPMethod method, String pattern, String scope) {
    }
}
