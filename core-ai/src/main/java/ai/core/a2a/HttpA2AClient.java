package ai.core.a2a;

import ai.core.api.a2a.AgentCard;
import ai.core.api.a2a.CancelTaskRequest;
import ai.core.api.a2a.GetTaskRequest;
import ai.core.api.a2a.SendMessageRequest;
import ai.core.api.a2a.SendMessageResponse;
import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.Task;
import ai.core.api.a2a.TaskState;
import ai.core.internal.http.PatchedHTTPClientBuilder;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.EventSource;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPHeaders;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

/**
 * HTTP/JSON binding client for A2A-compatible agents.
 *
 * @author xander
 */
public class HttpA2AClient implements A2AClient {
    private static final ContentType A2A_JSON = ContentType.parse("application/a2a+json");
    private static final ContentType TEXT_EVENT_STREAM = ContentType.parse("text/event-stream");

    public static Builder builder() {
        return new Builder();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl required");
        }
        var normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean isTerminal(A2AStreamEvent event) {
        if (event == null || event.statusUpdate == null || event.statusUpdate.status == null) return false;
        var state = event.statusUpdate.status.state;
        return state == TaskState.COMPLETED
                || state == TaskState.FAILED
                || state == TaskState.CANCELED
                || state == TaskState.REJECTED
                || state == TaskState.INPUT_REQUIRED
                || state == TaskState.AUTH_REQUIRED;
    }

    private final String baseUrl;
    private final String tenant;
    private final HTTPClient httpClient;
    private final Map<String, String> headers;

    HttpA2AClient(String baseUrl, String tenant, HTTPClient httpClient, Map<String, String> headers) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.tenant = tenant;
        this.httpClient = httpClient;
        this.headers = Map.copyOf(headers);
    }

    @Override
    public AgentCard getAgentCard() {
        if (tenant == null || tenant.isBlank()) {
            return get("/.well-known/agent-card.json", AgentCard.class);
        }
        try {
            return get("/agents/" + pathSegment(tenant) + "/.well-known/agent-card.json", AgentCard.class);
        } catch (IllegalStateException e) {
            return get("/.well-known/agent-card.json", AgentCard.class);
        }
    }

    @Override
    public A2AInvocationResult send(SendMessageRequest request) {
        applyTenant(request);
        var response = post("/message:send", request, SendMessageResponse.class);
        if (response.task != null) return A2AInvocationResult.ofTask(response.task);
        if (response.message != null) return A2AInvocationResult.ofMessage(response.message);
        var result = new A2AInvocationResult();
        result.response = response;
        return result;
    }

    @Override
    public Flow.Publisher<A2AStreamEvent> stream(SendMessageRequest request) {
        applyTenant(request);
        return subscriber -> {
            var publisher = new SubmissionPublisher<A2AStreamEvent>();
            publisher.subscribe(subscriber);
            var thread = new Thread(() -> consumeStream(request, publisher), "a2a-http-stream");
            thread.setDaemon(true);
            thread.start();
        };
    }

    @Override
    public Task getTask(GetTaskRequest request) {
        var id = requireTaskId(request != null ? request.id : null);
        return get("/tasks/" + pathSegment(id), Task.class);
    }

    @Override
    public Task cancelTask(CancelTaskRequest request) {
        var id = requireTaskId(request != null ? request.id : null);
        return post("/tasks/" + pathSegment(id) + "/cancel", request, Task.class);
    }

    private String requireTaskId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("taskId required");
        }
        return id;
    }

    private void consumeStream(SendMessageRequest request, SubmissionPublisher<A2AStreamEvent> publisher) {
        try (EventSource source = sse("/message:stream", request)) {
            for (var event : source) {
                if (event.data() == null || event.data().isBlank()) continue;
                var response = JsonUtil.fromJson(StreamResponse.class, event.data());
                var streamEvent = A2AStreamEvent.ofResponse(response);
                publisher.submit(streamEvent);
                if (isTerminal(streamEvent)) break;
            }
            publisher.close();
        } catch (Exception e) {
            publisher.closeExceptionally(e);
        }
    }

    private <T> T get(String path, Class<T> responseType) {
        var request = request(HTTPMethod.GET, path);
        return parse(httpClient.execute(request), responseType);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        var request = request(HTTPMethod.POST, path);
        request.body(JsonUtil.toJson(body), A2A_JSON);
        return parse(httpClient.execute(request), responseType);
    }

    private EventSource sse(String path, Object body) {
        var request = request(HTTPMethod.POST, path, TEXT_EVENT_STREAM);
        request.body(JsonUtil.toJson(body), A2A_JSON);
        var source = httpClient.sse(request);
        if (source.statusCode < 200 || source.statusCode >= 300) {
            source.close();
            throw new IllegalStateException("A2A stream request failed, statusCode=" + source.statusCode);
        }
        return source;
    }

    private HTTPRequest request(HTTPMethod method, String path) {
        return request(method, path, A2A_JSON);
    }

    private HTTPRequest request(HTTPMethod method, String path, ContentType accept) {
        var request = new HTTPRequest(method, baseUrl + path);
        request.accept(accept);
        for (var entry : headers.entrySet()) {
            request.headers.put(entry.getKey(), entry.getValue());
        }
        return request;
    }

    private <T> T parse(HTTPResponse response, Class<T> responseType) {
        if (response.statusCode < 200 || response.statusCode >= 300) {
            throw new IllegalStateException("A2A request failed, statusCode=" + response.statusCode + ", body=" + response.text());
        }
        return JsonUtil.fromJson(responseType, response.text());
    }

    private void applyTenant(SendMessageRequest request) {
        if (tenant == null || tenant.isBlank() || request == null || request.tenant != null) return;
        request.tenant = tenant;
    }

    public static class Builder {
        private String baseUrl;
        private String tenant;
        private HTTPClient httpClient;
        private Duration timeout = Duration.ofMinutes(10);
        private final Map<String, String> headers = new LinkedHashMap<>();

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder bearerToken(String token) {
            if (token != null && !token.isBlank()) {
                headers.put(HTTPHeaders.AUTHORIZATION, "Bearer " + token);
            }
            return this;
        }

        public Builder header(String name, String value) {
            if (name != null && value != null) {
                headers.put(name, value);
            }
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder httpClient(HTTPClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public HttpA2AClient build() {
            var client = httpClient != null
                    ? httpClient
                    : new PatchedHTTPClientBuilder().timeout(timeout).build();
            return new HttpA2AClient(baseUrl, tenant, client, headers);
        }
    }
}
