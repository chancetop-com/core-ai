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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
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
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;   // bound what a response can push into the pool
    private final HTTPClient httpClient = HTTPClient.builder()
        .connectTimeout(Duration.ofSeconds(10))
        .timeout(Duration.ofSeconds(60))
        .build();

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        var config = ctx.node().config();
        HTTPMethod method = method(str(config.get("method")));
        String url = ctx.pool().render(str(config.get("url"))).trim();
        if (url.isBlank()) {
            return new NodeOutcome.Fail("http node missing url", false);
        }
        String rejection = rejectUnsafeUrl(url);
        if (rejection != null) {
            return new NodeOutcome.Fail("http node url rejected: " + rejection, false);   // SSRF guard
        }
        var request = new HTTPRequest(method, url);
        applyHeaders(ctx, config.get("headers"), request);
        applyBody(ctx, config.get("body"), request);
        try {
            HTTPResponse response = httpClient.execute(request);
            if (response.body != null && response.body.length > MAX_RESPONSE_BYTES) {
                return new NodeOutcome.Fail("http response exceeds " + MAX_RESPONSE_BYTES + " bytes", false);
            }
            return new NodeOutcome.Normal(toOutput(response));
        } catch (RuntimeException e) {
            // only auto-retry idempotent methods — re-issuing a POST/PUT/DELETE after a post-send timeout
            // would duplicate the side effect.
            return new NodeOutcome.Fail("http call failed: " + e.getMessage(), idempotent(method));
        }
    }

    private void applyHeaders(NodeContext ctx, Object headers, HTTPRequest request) {
        if (headers instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                // strip CR/LF from key and rendered value so a templated value can't inject extra headers
                request.headers.put(stripCrlf(String.valueOf(entry.getKey())),
                    stripCrlf(ctx.pool().render(String.valueOf(entry.getValue()))));
            }
        }
    }

    private void applyBody(NodeContext ctx, Object body, HTTPRequest request) {
        if (body instanceof String template && !template.isBlank()) {
            // body is JSON; renderJson escapes substituted values so upstream content can't break/inject structure
            request.body(ctx.pool().renderJson(template).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);
        }
    }

    // Reject non-http(s) schemes and hosts that resolve to loopback / private / link-local / any-local / multicast
    // addresses (blocks SSRF to cloud metadata 169.254.169.254, localhost admin ports, internal services).
    // Best-effort: a small TOCTOU window remains vs DNS rebinding; full defense needs connection-time pinning.
    private static String rejectUnsafeUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "malformed url";
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return "only http/https is allowed";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "url has no host";
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (isBlocked(address)) {
                    return "resolves to a non-routable address (" + address.getHostAddress() + ")";
                }
            }
        } catch (UnknownHostException e) {
            return "cannot resolve host " + host;
        }
        return null;
    }

    private static boolean isBlocked(InetAddress address) {
        return address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress();
    }

    private static boolean idempotent(HTTPMethod method) {
        return method == HTTPMethod.GET || method == HTTPMethod.HEAD;
    }

    private static String stripCrlf(String value) {
        return value == null ? "" : value.replace("\r", "").replace("\n", "");
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
            return HTTPMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return HTTPMethod.GET;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
