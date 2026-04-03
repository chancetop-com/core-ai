package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
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
    private static final Set<String> SKIP_TAGS = Set.of("script", "style", "noscript", "meta", "link", "iframe", "embed", "object", "head");
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final String CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String TOOL_DESC = """
            - Fetches content from a specified URL and processes it using HTTP requests
            - Takes a URL, HTTP method, and optional output format as input
            - Supports GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS methods
            - Optional content type and request body for POST/PUT/PATCH requests
            - format: markdown (default) converts HTML to readable Markdown, text extracts plain text, html returns raw HTML
            - Returns the processed response body, or error message if request fails
            - Use this tool when you need to retrieve content from web URLs

            Usage notes:

            - IMPORTANT: If an MCP-provided web fetch tool is available, prefer using that tool instead of this one, as it may have fewer restrictions.
            - HTTP URLs will be automatically upgraded to HTTPS
            - For HTML pages, use markdown format (default) to get clean, token-efficient content
            - Results may be summarized if the content is very large
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
            var format = argsMap.get("format") instanceof String s ? s : "markdown";

            var result = executeRequest(url, method, contentType, body, format);
            return ToolCallResult.completed(result)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("url", url)
                    .withStats("method", method);
        } catch (Exception e) {
            var error = "Failed to parse web fetch arguments: " + e.getMessage();
            return ToolCallResult.failed(error, e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String executeRequest(String url, String method, String contentType, String body, String format) {
        if (Strings.isBlank(url)) return "Error: URL parameter is required";
        if (Strings.isBlank(method)) return "Error: HTTP method parameter is required";

        String httpMethod = method.toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(httpMethod)) {
            return "Error: Invalid HTTP method '" + method + "'. Supported methods: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS";
        }

        try {
            LOGGER.debug("Executing HTTP {} request to: {}", httpMethod, url);
            var request = buildRequest(url, httpMethod, contentType, body, format);
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // Cloudflare bot challenge detected — retry with simple UA
            if (response.statusCode() == 403 && "challenge".equals(response.headers().firstValue("cf-mitigated").orElse(""))) {
                LOGGER.debug("Cloudflare challenge detected, retrying with simple UA");
                var retry = HttpRequest.newBuilder(request, (n, v) -> true)
                        .header("User-Agent", "WebFetchTool/1.0")
                        .build();
                response = client.send(retry, HttpResponse.BodyHandlers.ofByteArray());
            }

            return handleResponse(response, format, url);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
        }
    }

    private HttpRequest buildRequest(String url, String httpMethod, String contentType, String body, String format) {
        var acceptHeader = switch (format) {
            case "text" -> "text/plain;q=1.0, text/html;q=0.8, */*;q=0.1";
            case "html" -> "text/html;q=1.0, application/xhtml+xml;q=0.9, */*;q=0.1";
            default -> "text/markdown;q=1.0, text/html;q=0.8, text/plain;q=0.7, */*;q=0.1";
        };

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", CHROME_USER_AGENT)
                .header("Accept", acceptHeader)
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(Duration.ofSeconds(60));

        if (!Strings.isBlank(contentType)) {
            requestBuilder.header("Content-Type", contentType);
        }

        HttpRequest.BodyPublisher bodyPublisher = Strings.isBlank(body)
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);

        switch (httpMethod) {
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

    private String handleResponse(HttpResponse<byte[]> response, String format, String url) {
        int statusCode = response.statusCode();
        byte[] responseBytes = response.body();

        if (statusCode >= 400) {
            String error = "HTTP request failed with status " + statusCode;
            LOGGER.warn(error);
            return error;
        }

        if (responseBytes.length > MAX_RESPONSE_BYTES) {
            return "Error: Response too large (exceeds 5MB limit)";
        }

        var responseContentType = response.headers().firstValue("content-type").orElse("");
        var responseBody = new String(responseBytes, StandardCharsets.UTF_8);

        LOGGER.debug("HTTP request completed with status: {}, content-type: {}, size: {} bytes", statusCode, responseContentType, responseBytes.length);

        if (responseContentType.contains("text/html")) {
            return switch (format) {
                case "text" -> extractText(responseBody);
                case "html" -> responseBody;
                default -> convertToMarkdown(responseBody, url);
            };
        }
        return responseBody;
    }

    private String extractText(String html) {
        var doc = Jsoup.parse(html);
        doc.select(String.join(", ", SKIP_TAGS)).remove();
        return doc.body().text();
    }

    private String convertToMarkdown(String html, String baseUrl) {
        var doc = Jsoup.parse(html, baseUrl);
        doc.select(String.join(", ", SKIP_TAGS)).remove();

        var sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(@NotNull Node node, int depth) {
                if (node instanceof TextNode text) {
                    var t = text.text();
                    if (!t.isBlank()) sb.append(t);
                } else if (node instanceof Element el) {
                    switch (el.tagName()) {
                        case "h1" -> sb.append("\n# ");
                        case "h2" -> sb.append("\n## ");
                        case "h3" -> sb.append("\n### ");
                        case "h4" -> sb.append("\n#### ");
                        case "h5" -> sb.append("\n##### ");
                        case "h6" -> sb.append("\n###### ");
                        case "li" -> sb.append("\n- ");
                        case "br" -> sb.append("\n");
                        case "code" -> sb.append("`");
                        case "pre" -> sb.append("\n```\n");
                        case "a" -> {
                            var href = el.attr("abs:href");
                            if (!href.isEmpty()) sb.append("[");
                        }
                        case "strong", "b" -> sb.append("**");
                        case "em", "i" -> sb.append("*");
                        default -> { }
                    }
                }
            }

            @Override
            public void tail(@NotNull Node node, int depth) {
                if (node instanceof Element el) {
                    switch (el.tagName()) {
                        case "h1", "h2", "h3", "h4", "h5", "h6", "p", "div", "li", "tr" -> sb.append("\n");
                        case "pre" -> sb.append("\n```\n");
                        case "code" -> sb.append("`");
                        case "a" -> {
                            var href = el.attr("abs:href");
                            if (!href.isEmpty()) sb.append("](").append(href).append(")");
                        }
                        case "strong", "b" -> sb.append("**");
                        case "em", "i" -> sb.append("*");
                        default -> { }
                    }
                }
            }
        }, doc.body());

        return sb.toString().replaceAll("\n{3,}", "\n\n").trim();
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
                    ToolCallParameters.ParamSpec.of(String.class, "format", "Output format for HTML pages: markdown (default, clean and token-efficient), text (plain text), html (raw HTML)").enums(List.of("markdown", "text", "html")),
                    ToolCallParameters.ParamSpec.of(String.class, "content_type", "The content type header (e.g., application/json, text/html, application/xml)"),
                    ToolCallParameters.ParamSpec.of(String.class, "body", "The request body (optional, used for POST/PUT/PATCH requests)")
            ));
            var tool = new WebFetchTool();
            build(tool);
            return tool;
        }
    }
}
