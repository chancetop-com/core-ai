package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPHeaders;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
public class WebFetchTool extends ToolCall {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebFetchTool.class);
    private static final String BROWSER_DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    private static final String WEB_FETCH_TOOL_DESC = """
            - Fetches content from a specified URL and processes it using HTTP requests
            - Takes a URL and HTTP method as input
            - Supports GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS methods
            - Optional content type and request body for POST/PUT/PATCH requests
            - Returns the response body as string, or error message if request fails
            - Use this tool when you need to retrieve content from web URLs
            """;

    public static Builder builder() {
        return new Builder();
    }

    private final HTTPClient client = HTTPClient.builder().build();

    @Override
    public String call(String text) {
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var url = (String) argsMap.get("url");
            var method = (String) argsMap.get("method");
            var contentType = (String) argsMap.get("content_type");
            var body = (String) argsMap.get("body");

            return executeRequest(url, method, contentType, body);
        } catch (Exception e) {
            var error = "Failed to parse web fetch arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    /**
     * Execute HTTP request
     *
     * @param url the URL to request
     * @param method the HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param contentType the content type header
     * @param body the request body (optional, can be null)
     * @return the response body as string, or error message if request fails
     */
    private String executeRequest(String url, String method, String contentType, String body) {
        // Validate parameters
        if (Strings.isBlank(url)) {
            return "Error: URL parameter is required";
        }
        if (Strings.isBlank(method)) {
            return "Error: HTTP method parameter is required";
        }

        // Parse and validate HTTP method
        HTTPMethod httpMethod;
        try {
            httpMethod = HTTPMethod.valueOf(method.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return "Error: Invalid HTTP method '" + method + "'. Supported methods: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS";
        }

        try {
            LOGGER.info("Executing HTTP {} request to: {}", httpMethod, url);

            // Create request
            var request = new HTTPRequest(httpMethod, url);
            request.headers.put(HTTPHeaders.USER_AGENT, BROWSER_DEFAULT_USER_AGENT);

            // Set content type if provided
            if (!Strings.isBlank(contentType)) {
                request.headers.put(HTTPHeaders.CONTENT_TYPE, contentType);
            }

            // Set body if provided
            if (!Strings.isBlank(body)) {
                ContentType ct = parseContentType(contentType);
                request.body(body.getBytes(StandardCharsets.UTF_8), ct);
                LOGGER.debug("Request body length: {} bytes", body.length());
            }

            // Execute request
            HTTPResponse response = client.execute(request);
            int statusCode = response.statusCode;
            String responseText = response.text();

            LOGGER.info("HTTP request completed with status code: {}, response length: {} bytes",
                statusCode, responseText.length());

            // Check status code
            if (statusCode >= 400) {
                String error = "HTTP request failed with status " + statusCode + ": " + responseText;
                LOGGER.warn(error);
                return error;
            }

            return responseText;

        } catch (Exception e) {
            String error = "HTTP request failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    /**
     * Parse content type string to ContentType enum
     */
    private ContentType parseContentType(String contentType) {
        if (Strings.isBlank(contentType)) {
            return ContentType.APPLICATION_JSON;
        }

        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("json")) {
            return ContentType.APPLICATION_JSON;
        } else if (ct.contains("xml")) {
            return ContentType.TEXT_XML;
        } else if (ct.contains("html")) {
            return ContentType.TEXT_HTML;
        } else if (ct.contains("plain")) {
            return ContentType.TEXT_PLAIN;
        } else if (ct.contains("form-urlencoded")) {
            return ContentType.APPLICATION_FORM_URLENCODED;
        }

        // Default to JSON
        return ContentType.APPLICATION_JSON;
    }

    public static class Builder extends ToolCall.Builder<Builder, WebFetchTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public WebFetchTool build() {
            this.name("web_fetch");
            this.description(WEB_FETCH_TOOL_DESC);
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
