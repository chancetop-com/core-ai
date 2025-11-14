package ai.core.mcp.client;

import core.framework.http.EventSource;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPClientException;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.util.StopWatch;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;

/**
 * Advanced MCP HTTPClient wrapper with full Accept header control.
 * <p>
 * This implementation uses reflection to access the underlying OkHttpClient
 * and makes direct HTTP calls to preserve custom headers including Accept.
 * <p>
 * This is a workaround for HTTPClient.sse() overwriting the Accept header.
 *
 * @author stephen
 */
public final class McpHTTPClientAdvanced implements HTTPClient {
    /**
     * Create a new MCP-compliant HTTP client with default settings.
     */
    public static McpHTTPClientAdvanced create() {
        HTTPClient client = HTTPClient.builder()
            .connectTimeout(Duration.ofMillis(500))
            .timeout(Duration.ofSeconds(10))
            .build();
        return new McpHTTPClientAdvanced(client);
    }

    private final HTTPClient delegate;
    private final OkHttpClient okHttpClient;

    private McpHTTPClientAdvanced(HTTPClient delegate) {
        this.delegate = delegate;
        this.okHttpClient = extractOkHttpClient(delegate);
    }

    /**
     * Extract the underlying OkHttpClient using reflection.
     */
    private OkHttpClient extractOkHttpClient(HTTPClient client) {
        try {
            // Access the private 'client' field from HTTPClientImpl
            Class<?> implClass = client.getClass();
            Field clientField = implClass.getDeclaredField("client");
            clientField.setAccessible(true);
            return (OkHttpClient) clientField.get(client);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to extract OkHttpClient from HTTPClient. "
                + "This may be due to framework version incompatibility.", e);
        }
    }
    
    @Override
    public HTTPResponse execute(HTTPRequest request) {
        return delegate.execute(request);
    }
    
    /**
     * MCP-compliant SSE implementation that preserves custom Accept header.
     * <p>
     * This method bypasses the delegate.sse() and makes a direct HTTP call
     * using the underlying OkHttpClient, preserving all custom headers.
     */
    @Override
    public EventSource sse(HTTPRequest request) {
        var watch = new StopWatch();
        
        // Set default MCP-compliant Accept header if not present
        if (!request.headers.containsKey("Accept")) {
            request.headers.put("Accept", "application/json, text/event-stream");
        }
        
        // Validate Accept header
        String accept = request.headers.get("Accept");
        if (!accept.contains("text/event-stream")) {
            throw new HTTPClientException(
                "Accept header must include text/event-stream for SSE requests, got: " + accept,
                "INVALID_ACCEPT_HEADER"
            );
        }
        
        // Build OkHttp request with custom headers
        Request.Builder builder = new Request.Builder()
            .url(request.requestURI());
        
        // Add all custom headers
        for (Map.Entry<String, String> entry : request.headers.entrySet()) {
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        
        // Set request body if present
        if (request.body != null) {
            MediaType contentType = request.contentType != null 
                ? MediaType.parse(request.contentType.toString()) 
                : null;
            builder.method(request.method.name(), RequestBody.create(request.body, contentType));
        } else {
            builder.method(request.method.name(), null);
        }
        
        Request okHttpRequest = builder.build();
        int requestBodyLength = request.body == null ? 0 : request.body.length;
        
        try {
            Response response = okHttpClient.newCall(okHttpRequest).execute();
            int statusCode = response.code();
            
            // Validate response
            String contentType = response.header("Content-Type");
            if (statusCode != 200 || contentType == null || !contentType.startsWith("text/event-stream")) {
                response.close();
                throw new HTTPClientException(
                    String.format("Invalid SSE response, statusCode=%d, content-type=%s", statusCode, contentType),
                    "HTTP_REQUEST_FAILED"
                );
            }
            
            // Create EventSource with the response body
            // Note: EventSource constructor signature may vary by framework version
            return createEventSource(response, requestBodyLength, watch.elapsed());
            
        } catch (IOException e) {
            throw new HTTPClientException(
                String.format("SSE request failed, uri=%s, error=%s", request.uri, e.getMessage()),
                "HTTP_REQUEST_FAILED",
                e
            );
        }
    }
    
    /**
     * Create EventSource from OkHttp Response.
     * This method adapts the OkHttp response to the EventSource expected by the framework.
     */
    private EventSource createEventSource(Response response, int requestBodyLength, long elapsed) {
        try {
            // Extract headers
            var headers = new java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            okhttp3.Headers okHeaders = response.headers();
            for (int i = 0; i < okHeaders.size(); i++) {
                headers.put(okHeaders.name(i), okHeaders.value(i));
            }
            
            // Create EventSource
            // Note: Constructor signature: EventSource(int statusCode, Map<String, String> headers, 
            //                                          ResponseBody body, int requestLength, long elapsed)
            return new EventSource(
                response.code(),
                headers,
                response.body(),
                requestBodyLength,
                elapsed
            );
        } catch (Exception e) {
            response.close();
            throw new HTTPClientException("Failed to create EventSource from response", "EVENT_SOURCE_ERROR", e);
        }
    }
}

