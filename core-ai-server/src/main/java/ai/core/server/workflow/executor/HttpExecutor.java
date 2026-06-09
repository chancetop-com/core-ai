package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP node: calls an external endpoint with a templated url / headers / body and returns the response as
 * {@code {status, headers, body}} JSON, so a downstream node can read e.g. {@code nodes.http1.output.body.id}
 * or branch on {@code nodes.http1.output.status}. A transport failure (timeout / connection) is a retryable
 * Fail (RetryingNodeExecutor re-attempts); any HTTP response — including 4xx/5xx — is a normal completion, so
 * status handling stays in the graph (an IF_ELSE on the status). config: {@code method} (default GET),
 * {@code url} (required, templated), optional {@code headers} (object, values templated), {@code body} (templated).
 *
 * @author Xander
 */
public class HttpExecutor implements NodeExecutor {
    // todo: per-node timeout + custom body content-type are deferred; one shared client with sane defaults for now.
    private final HTTPClient httpClient = HTTPClient.builder()
        .connectTimeout(Duration.ofSeconds(10))
        .timeout(Duration.ofSeconds(60))
        .build();

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        var config = ctx.node().config();
        String url = ctx.pool().render(str(config.get("url"))).trim();
        if (url.isBlank()) {
            return new NodeOutcome.Fail("http node missing url", false);
        }
        var request = new HTTPRequest(method(str(config.get("method"))), url);
        applyHeaders(ctx, config.get("headers"), request);
        applyBody(ctx, config.get("body"), request);
        try {
            HTTPResponse response = httpClient.execute(request);
            return new NodeOutcome.Normal(toOutput(response));
        } catch (RuntimeException e) {
            return new NodeOutcome.Fail("http call failed: " + e.getMessage(), true);
        }
    }

    private void applyHeaders(NodeContext ctx, Object headers, HTTPRequest request) {
        if (headers instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                request.headers.put(String.valueOf(entry.getKey()), ctx.pool().render(String.valueOf(entry.getValue())));
            }
        }
    }

    private void applyBody(NodeContext ctx, Object body, HTTPRequest request) {
        if (body instanceof String template && !template.isBlank()) {
            request.body(ctx.pool().render(template).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        }
    }

    private String toOutput(HTTPResponse response) {
        var out = new LinkedHashMap<String, Object>();
        out.put("status", response.statusCode);
        out.put("headers", response.headers);
        out.put("body", parseBody(response.text()));   // parse JSON so downstream can navigate into it
        return JSON.toJSON(out);
    }

    private static Object parseBody(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            return JSON.fromJSON(Map.class, text);
        } catch (RuntimeException e) {
            return text;   // not a JSON object (text / array / scalar) -> keep the raw string
        }
    }

    private static HTTPMethod method(String value) {
        if (value == null || value.isBlank()) {
            return HTTPMethod.GET;
        }
        try {
            return HTTPMethod.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return HTTPMethod.GET;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
