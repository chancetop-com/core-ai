package ai.core.sse;

import java.util.Map;

/**
 * Runs before an SSE handler commits HTTP 200. A failed check can still return a real 404/401/409.
 *
 * @author stephen
 */
@FunctionalInterface
public interface SsePreflight {
    Result check(Request request);

    record Request(String method, String path, String host, Map<String, String> headers, Map<String, String> queryParams) {
    }

    record Result(int status, String body) {
        public static Result ok() {
            return new Result(200, null);
        }

        public static Result error(int status, String body) {
            return new Result(status, body);
        }
    }
}
