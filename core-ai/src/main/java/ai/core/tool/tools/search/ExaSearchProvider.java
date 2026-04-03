package ai.core.tool.tools.search;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exa AI search provider via MCP endpoint (free, no API key required).
 * Returns full page content optimized for LLM consumption.
 *
 * @author stephen
 */
public class ExaSearchProvider implements SearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExaSearchProvider.class);
    private static final String EXA_MCP_URL = "https://mcp.exa.ai/mcp";
    private static final int CONTEXT_MAX_CHARACTERS = 10000;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public List<SearchResult> search(String query, List<String> allowedDomains, List<String> blockedDomains, int maxResults) {
        LOGGER.debug("Exa MCP search: {}", query);

        var arguments = new HashMap<String, Object>();
        arguments.put("query", query);
        arguments.put("numResults", maxResults);
        arguments.put("livecrawl", "fallback");
        arguments.put("contextMaxCharacters", CONTEXT_MAX_CHARACTERS);
        if (allowedDomains != null && !allowedDomains.isEmpty()) arguments.put("includeDomains", allowedDomains);
        if (blockedDomains != null && !blockedDomains.isEmpty()) arguments.put("excludeDomains", blockedDomains);

        var body = Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tools/call",
                "params", Map.of("name", "web_search_exa", "arguments", arguments)
        );

        var request = HttpRequest.newBuilder()
                .uri(URI.create(EXA_MCP_URL))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSON(body), StandardCharsets.UTF_8))
                .build();

        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Exa MCP search failed with status " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (Exception e) {
            throw new RuntimeException("Exa MCP search request failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<SearchResult> parseResponse(String responseText) {
        // response may be SSE (data: {...}) or plain JSON
        String jsonText = responseText;
        for (String line : responseText.split("\n")) {
            if (line.startsWith("data: ")) {
                jsonText = line.substring(6).trim();
                break;
            }
        }

        Map<String, Object> data = JSON.fromJSON(Map.class, jsonText);
        var result = (Map<String, Object>) data.get("result");
        if (result == null) return List.of();

        var content = (List<Map<String, Object>>) result.get("content");
        if (content == null || content.isEmpty()) return List.of();

        var text = (String) content.get(0).get("text");
        if (Strings.isBlank(text)) return List.of();

        // MCP returns pre-formatted full-page content as a single text block
        return List.of(new SearchResult("", "", text));
    }
}
