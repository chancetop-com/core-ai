package ai.core.server.a2a;

import ai.core.server.messaging.PodRegistry;
import ai.core.server.web.InternalTokenAuth;
import ai.core.sse.SsePreflight;

/**
 * Pre-200 validation for the owner pod's A2A event stream. External hosts see 404; the internal
 * token authenticates the calling pod.
 *
 * @author xander
 */
public class InternalA2APreflight implements SsePreflight {
    public static final String PATH = "/_internal/a2a/events";
    private final PodRegistry podRegistry;
    private final String token;
    private final String previousToken;

    public InternalA2APreflight(PodRegistry podRegistry, String token, String previousToken) {
        this.podRegistry = podRegistry;
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
        var taskId = request.queryParams().get("task_id");
        if (taskId == null || taskId.isBlank()) {
            return Result.error(400, "{\"errorCode\":\"BAD_REQUEST\",\"message\":\"task_id is required\"}");
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
