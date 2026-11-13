package ai.core.server.share;

import core.framework.api.http.HTTPStatus;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Response;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies cache and referrer restrictions to sharing routes and bounds anonymous request traffic.
 *
 * @author Xander
 */
public class SessionShareInterceptor implements Interceptor {
    private static final int MAX_CLIENTS = 4096;
    private static final int REQUESTS_PER_MINUTE = 240;
    public static Response headers(Response response) {
        return response.header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer")
            .header("X-Robots-Tag", "noindex, nofollow, noarchive")
            .header("X-Content-Type-Options", "nosniff");
    }

    private final Map<String, Window> clients = new LinkedHashMap<>();
    private final Clock clock;

    public SessionShareInterceptor() {
        this(Clock.systemUTC());
    }

    SessionShareInterceptor(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var path = request.path();
        boolean anonymous = path.startsWith("/api/public/session-shares/") || path.startsWith("/shared/sessions/");
        boolean management = path.startsWith("/api/sessions/") && path.contains("/shares");
        if (!anonymous && !management) return invocation.proceed();
        if (anonymous && !allow(request.clientIP())) {
            return headers(Response.text("Too many sharing requests. Please retry.")
                .status(HTTPStatus.TOO_MANY_REQUESTS).header("Retry-After", "60"));
        }
        return headers(invocation.proceed());
    }

    boolean allow(String client) {
        synchronized (clients) {
            long now = clock.millis();
            var window = clients.get(client);
            if (window == null || now - window.startedAt >= 60_000) {
                if (clients.size() >= MAX_CLIENTS) clients.remove(clients.keySet().iterator().next());
                clients.put(client, new Window(now, 1));
                return true;
            }
            if (window.count >= REQUESTS_PER_MINUTE) return false;
            clients.put(client, new Window(window.startedAt, window.count + 1));
            return true;
        }
    }

    private record Window(long startedAt, int count) {
    }
}
