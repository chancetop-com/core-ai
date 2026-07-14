package ai.core.cli.trace;

import ai.core.cli.DebugLog;
import ai.core.utils.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posts CLI traces to the authenticated server endpoint. Best-effort: all failures are swallowed
 * into the debug log so trace upload never disrupts the REPL.
 *
 * @author Xander
 */
public class HttpTraceUploader implements TraceUploader {
    private static final String PATH = "/api/traces/ingest";

    // Process-wide shared executor and HttpClient. A new uploader is created per CLI session
    // (ACP/A2A/session-manager modes), so per-instance daemon threads would leak unbounded.
    // Sharing keeps a single daemon worker and one connection pool for all best-effort uploads.
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "cli-trace-upload");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    // Reflection-free conversion to the IngestRequest JSON shape (keys match server IngestSpanRequest fields).
    static Map<String, Object> toMap(CliTraceRequest request) {
        var spans = new ArrayList<Map<String, Object>>();
        if (request.spans != null) {
            for (var s : request.spans) {
                spans.add(spanToMap(s));
            }
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("serviceName", request.serviceName);
        m.put("serviceVersion", request.serviceVersion);
        m.put("environment", request.environment);
        m.put("spans", spans);
        return m;
    }

    private static Map<String, Object> spanToMap(CliTraceSpan s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("traceId", s.traceId);
        m.put("spanId", s.spanId);
        m.put("parentSpanId", s.parentSpanId);
        m.put("name", s.name);
        m.put("type", s.type);
        m.put("model", s.model);
        m.put("input", s.input);
        m.put("output", s.output);
        m.put("inputTokens", s.inputTokens);
        m.put("outputTokens", s.outputTokens);
        m.put("cachedTokens", s.cachedTokens);
        m.put("durationMs", s.durationMs);
        m.put("status", s.status);
        m.put("attributes", s.attributes);
        m.put("startedAtEpochMs", s.startedAtEpochMs);
        m.put("completedAtEpochMs", s.completedAtEpochMs);
        return m;
    }

    private static String stripTrailingSlash(String url) {
        var u = url;
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private final String endpoint;
    private final String apiKey;

    public HttpTraceUploader(String serverUrl, String apiKey) {
        this.endpoint = stripTrailingSlash(serverUrl) + PATH;
        this.apiKey = apiKey;
    }

    @Override
    public void upload(CliTraceRequest request) {
        EXECUTOR.submit(() -> send(request));
    }

    private void send(CliTraceRequest request) {
        try {
            // Serialize via plain Map/List, not the typed DTO: in the GraalVM native image Jackson has no
            // reflection metadata for CliTraceRequest/CliTraceSpan and throws "No serializer found". Maps,
            // Lists and primitives use built-in serializers that need no per-class reflection.
            var body = JsonUtil.toJson(toMap(request));
            var httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = HTTP_CLIENT.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                DebugLog.log("trace upload failed: status=" + response.statusCode() + " body=" + response.body());
            }
        } catch (Exception e) {
            DebugLog.log("trace upload error", e);
        }
    }
}
