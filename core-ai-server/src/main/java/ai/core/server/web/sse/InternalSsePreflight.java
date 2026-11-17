package ai.core.server.web.sse;

import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.web.InternalTokenAuth;
import ai.core.sse.SsePreflight;

/**
 * Pre-200 validation for the owner-pod event stream. External hosts see 404; this is the only
 * place the internal route is authenticated, since {@code SseAuthInterceptor} runs after 200.
 *
 * @author stephen
 */
public class InternalSsePreflight implements SsePreflight {
    private final PodRegistry podRegistry;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final String token;
    private final String previousToken;

    public InternalSsePreflight(PodRegistry podRegistry, SessionOwnershipRegistry ownershipRegistry,
                                String token, String previousToken) {
        this.podRegistry = podRegistry;
        this.ownershipRegistry = ownershipRegistry;
        this.token = token;
        this.previousToken = previousToken;
    }

    @Override
    public Result check(Request request) {
        if (!podRegistry.host().equals(request.host())) {
            return Result.error(404, "{\"errorCode\":\"NOT_FOUND\"}");
        }
        if (!InternalTokenAuth.matches(header(request, "X-Internal-Token"), token, previousToken)) {
            return Result.error(401, "{\"errorCode\":\"UNAUTHORIZED\"}");
        }
        var sessionId = request.queryParams().get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            return Result.error(400, "{\"errorCode\":\"BAD_REQUEST\",\"message\":\"session_id is required\"}");
        }
        var after = request.queryParams().get("after");
        if (after != null && !after.isBlank()) {
            try {
                Long.parseLong(after);
            } catch (NumberFormatException e) {
                return Result.error(400, "{\"errorCode\":\"BAD_REQUEST\",\"message\":\"after must be a number\"}");
            }
        }
        if (!ownershipRegistry.isOwner(sessionId)) {
            return Result.error(409, "{\"errorCode\":\"NOT_OWNER\"}");
        }
        return Result.ok();
    }

    private String header(Request request, String name) {
        var direct = request.headers().get(name);
        if (direct != null) return direct;
        for (var entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }
}
