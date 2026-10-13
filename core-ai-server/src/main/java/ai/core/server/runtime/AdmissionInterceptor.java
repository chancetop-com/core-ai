package ai.core.server.runtime;

import ai.core.server.web.auth.AuthContext;
import core.framework.http.HTTPMethod;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Response;
import core.framework.web.WebContext;

/**
 * Entry admission for the requests that create work (new sessions, chat turns, SSE channels, runs,
 * channel webhooks, gateway traffic). Read-only and infrastructure paths are never guarded, so a
 * saturated platform still serves admin, observability, artifact download and health-check traffic.
 * Enforcement is opt-in: with {@code runtime.admission.enabled} off the interceptor only records what
 * it would have rejected.
 *
 * @author stephen
 */
public class AdmissionInterceptor implements Interceptor {
    /** Only work-creating calls are guarded; listing sessions and runs stays available while saturated. */
    static boolean guarded(HTTPMethod method, String path) {
        if (path == null) return false;
        if (method == HTTPMethod.POST) {
            return "/api/sessions".equals(path)
                    || "/api/sessions/messages/stream".equals(path)
                    || "/api/runs".equals(path)
                    || path.startsWith("/api/channels/")
                    || path.startsWith("/api/gateway/v1/");
        }
        return method == HTTPMethod.PUT && "/api/sessions/events".equals(path);
    }

    private final RuntimeCapacityGuard guard;

    public AdmissionInterceptor(RuntimeCapacityGuard guard) {
        this.guard = guard;
    }

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        if (!guarded(request.method(), request.path())) return invocation.proceed();

        var scope = guard.saturatedScope();
        if (scope == null) scope = saturatedUserScope(invocation.context(), request.path());
        if (scope == null) return invocation.proceed();

        guard.recordSaturated(scope);
        if (!guard.limits().admissionEnabled) return invocation.proceed();

        guard.recordRejected(scope);
        throw new CapacityExceededException(scope, guard.retryAfterSeconds(),
                "platform is at capacity (" + scope + "), retry later");
    }

    /** Chat carries the user in the request identity, so per-user turn fairness applies there only. */
    private String saturatedUserScope(WebContext context, String path) {
        if (!"/api/sessions/messages/stream".equals(path)) return null;
        return guard.saturatedUserScope(AuthContext.userId(context));
    }
}
