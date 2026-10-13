package ai.core.server.runtime;

import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.ResponseStatus;
import core.framework.log.ErrorCode;
import core.framework.log.Severity;

import java.io.Serial;

/**
 * Thrown when the platform itself is out of capacity for the requested work (heap watermark, in-flight
 * turn limit, per-user or per-agent concurrency). Distinct from QUOTA_EXCEEDED, which is the caller's
 * own token budget: this one means "retry later", and every response carries Retry-After.
 *
 * @author stephen
 */
@ResponseStatus(HTTPStatus.TOO_MANY_REQUESTS)
public class CapacityExceededException extends RuntimeException implements ErrorCode {
    @Serial
    private static final long serialVersionUID = 5170319247894208351L;

    public static final String ERROR_CODE = "CAPACITY_EXCEEDED";

    private final String scope;
    private final int retryAfterSeconds;

    public CapacityExceededException(String scope, int retryAfterSeconds, String message) {
        this(scope, retryAfterSeconds, message, null);
    }

    public CapacityExceededException(String scope, int retryAfterSeconds, String message, Throwable cause) {
        super(message, cause);
        this.scope = scope;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** Which limit tripped: heap, pod_turns, user_sessions, user_turns, run_queue, agent_concurrency, sandboxes. */
    public String scope() {
        return scope;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public Severity severity() {
        return Severity.WARN;
    }

    @Override
    public String errorCode() {
        return ERROR_CODE;
    }
}
