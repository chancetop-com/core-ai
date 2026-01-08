package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author stephen
 */
public class WebFetchTool extends ToolCall {
    public static final String TOOL_NAME = "web_fetch";

    private static final Logger LOGGER = LoggerFactory.getLogger(WebFetchTool.class);
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    private static final String TOOL_DESC = """
            - Fetches content from a specified URL and processes it using HTTP requests
            - Takes a URL and HTTP method as input
            - Supports GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS methods
            - Optional content type and request body for POST/PUT/PATCH requests
            - Returns the response body as string, or error message if request fails
            - Use this tool when you need to retrieve content from web URLs

            Usage notes:

            - IMPORTANT: If an MCP-provided web fetch tool is available, prefer using that tool instead of this one, as it may have fewer restrictions.
            - HTTP URLs will be automatically upgraded to HTTPS
            - The prompt should describe what information you want to extract from the page
            - This tool is read-only and does not modify any files
            - Results may be summarized if the content is very large
            - Includes a self-cleaning 15-minute cache for faster responses when repeatedly accessing the same URL
            - When a URL redirects to a different host, the tool will inform you and provide the redirect URL in a special format. You should then make a new WebFetch request with the redirect URL to fetch the content.
            """;

    public static Builder builder() {
        return new Builder();
    }

    private final HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build();

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var url = (String) argsMap.get("url");
            var method = (String) argsMap.get("method");
            var contentType = (String) argsMap.get("content_type");
            var body = (String) argsMap.get("body");

            var result = executeRequest(url, method, contentType, body);
            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("url", url)
                .withStats("method", method);
        } catch (Exception e) {
            var error = "Failed to parse web fetch arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String executeRequest(String url, String method, String contentType, String body) {
        if (Strings.isBlank(url)) {
            return "Error: URL parameter is required";
        }
        if (Strings.isBlank(method)) {
            return "Error: HTTP method parameter is required";
        }

        String httpMethod = method.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(httpMethod)) {
            return "Error: Invalid HTTP method '" + method + "'. Supported methods: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS";
        }

        try {
            LOGGER.info("Executing HTTP {} request to: {}", httpMethod, url);
            HttpRequest request = buildRequest(url, httpMethod, contentType, body);
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return handleResponse(response);
        } catch (Exception e) {
            String error = "HTTP request failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private HttpRequest buildRequest(String url, String httpMethod, String contentType, String body) {
        var requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "core-ai/1.0")
            .header("Accept", "*/*")
            .timeout(Duration.ofSeconds(60));

        if (!Strings.isBlank(contentType)) {
            requestBuilder.header("Content-Type", contentType);
        }

        HttpRequest.BodyPublisher bodyPublisher = Strings.isBlank(body)
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);

        switch (httpMethod) {
            case "GET" -> requestBuilder.GET();
            case "POST" -> requestBuilder.POST(bodyPublisher);
            case "PUT" -> requestBuilder.PUT(bodyPublisher);
            case "DELETE" -> requestBuilder.DELETE();
            case "PATCH" -> requestBuilder.method("PATCH", bodyPublisher);
            case "HEAD" -> requestBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
            case "OPTIONS" -> requestBuilder.method("OPTIONS", HttpRequest.BodyPublishers.noBody());
            default -> requestBuilder.GET();
        }
        return requestBuilder.build();
    }

    private String handleResponse(HttpResponse<String> response) {
        int statusCode = response.statusCode();
        String responseText = response.body();
        LOGGER.info("HTTP request completed with status code: {}, response length: {} bytes", statusCode, responseText.length());

        if (statusCode >= 400) {
            String error = "HTTP request failed with status " + statusCode + ": " + responseText;
            LOGGER.warn(error);
            return error;
        }
        return responseText;
    }

    public static class Builder extends ToolCall.Builder<Builder, WebFetchTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public WebFetchTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "url", "The URL to fetch content from").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "method", "The HTTP method (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS)").required().enums(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")),
                    ToolCallParameters.ParamSpec.of(String.class, "content_type", "The content type header (e.g., application/json, text/html, application/xml)"),
                    ToolCallParameters.ParamSpec.of(String.class, "body", "The request body (optional, used for POST/PUT/PATCH requests)")
            ));
            var tool = new WebFetchTool();
            build(tool);
            return tool;
        }
    }
}
