package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallResult;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPHeaders;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class WebSearchTool extends ToolCall {
    public static final String TOOL_NAME = "web_search";

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSearchTool.class);
    private static final String BROWSER_DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    private static final String TOOL_DESC = Strings.format("""
            - Search the web and use the results to inform responses
            - Provides up-to-date information for current events and recent data
            - Returns search result information formatted as search result blocks, including links as markdown hyperlinks
            - Use this tool for accessing information beyond LLM model's knowledge cutoff
            - Searches are performed automatically within a single API call

            CRITICAL REQUIREMENT - You MUST follow this:
              - After answering the user's question, you MUST include a "Sources:" section at the end of your response
              - In the Sources section, list all relevant URLs from the search results as markdown hyperlinks: [Title](URL)
              - This is MANDATORY - never skip including sources in your response
              - Example format:

                [Your answer here]

                Sources:
                - [Source Title 1](https://example.com/1)
                - [Source Title 2](https://example.com/2)

            Usage notes:
              - Domain filtering is supported to include or block specific websites

            IMPORTANT - Use the correct year in search queries:
              - Today's date is {}. You MUST use this year when searching for recent information, documentation, or current events.
              - Example: If today is 2025-07-15 and the user asks for "latest React docs", search for "React documentation 2025", NOT "React documentation 2024"
            """, ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate());

    public static Builder builder() {
        return new Builder();
    }

    private final HTTPClient client = HTTPClient.builder().build();
    private String searchApiEndpoint;
    private String apiKey;
    private int maxResults = 10;

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text) {
        var startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var query = (String) argsMap.get("query");
            var allowedDomains = (List<String>) argsMap.get("allowed_domains");
            var blockedDomains = (List<String>) argsMap.get("blocked_domains");

            if (Strings.isBlank(query)) {
                return ToolCallResult.failed("Error: query parameter is required")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var result = executeSearch(query, allowedDomains, blockedDomains);
            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("query", query);
        } catch (Exception e) {
            var error = "Failed to execute web search: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String executeSearch(String query, List<String> allowedDomains, List<String> blockedDomains) {
        var modifiedQuery = buildQueryWithDomainFilters(query, allowedDomains, blockedDomains);

        LOGGER.info("Executing web search: {}", modifiedQuery);

        try {
            var encodedQuery = URLEncoder.encode(modifiedQuery, StandardCharsets.UTF_8);
            var url = buildSearchUrl(encodedQuery);

            var request = new HTTPRequest(HTTPMethod.GET, url);
            request.headers.put(HTTPHeaders.USER_AGENT, BROWSER_DEFAULT_USER_AGENT);

            if (!Strings.isBlank(apiKey)) {
                request.headers.put("Authorization", "Bearer " + apiKey);
            }

            var response = client.execute(request);
            var statusCode = response.statusCode;
            var responseText = response.text();

            LOGGER.info("Web search completed with status code: {}, response length: {} bytes",
                statusCode, responseText.length());

            if (statusCode >= 400) {
                String error = "Web search failed with status " + statusCode + ": " + responseText;
                LOGGER.warn(error);
                return error;
            }

            return formatSearchResults(responseText);

        } catch (Exception e) {
            String error = "Web search failed: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private String buildQueryWithDomainFilters(String query, List<String> allowedDomains, List<String> blockedDomains) {
        var modifiedQuery = new StringBuilder(query);

        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            modifiedQuery.append(" (");
            for (int i = 0; i < allowedDomains.size(); i++) {
                if (i > 0) modifiedQuery.append(" OR ");
                modifiedQuery.append("site:").append(allowedDomains.get(i));
            }
            modifiedQuery.append(')');
        }

        if (blockedDomains != null && !blockedDomains.isEmpty()) {
            for (String domain : blockedDomains) {
                modifiedQuery.append(" -site:").append(domain);
            }
        }

        return modifiedQuery.toString();
    }

    private String buildSearchUrl(String encodedQuery) {
        if (!Strings.isBlank(searchApiEndpoint)) {
            if (searchApiEndpoint.contains("?")) {
                return searchApiEndpoint + "&q=" + encodedQuery + "&count=" + maxResults;
            }
            return searchApiEndpoint + "?q=" + encodedQuery + "&count=" + maxResults;
        }
        return "https://api.bing.microsoft.com/v7.0/search?q=" + encodedQuery + "&count=" + maxResults;
    }

    @SuppressWarnings("unchecked")
    private String formatSearchResults(String responseText) {
        try {
            Map<String, Object> response = JSON.fromJSON(Map.class, responseText);

            var webPages = (Map<String, Object>) response.get("webPages");
            if (webPages == null) {
                return "No search results found.";
            }

            var results = (List<Map<String, Object>>) webPages.get("value");
            if (results == null || results.isEmpty()) {
                return "No search results found.";
            }

            StringBuilder formatted = new StringBuilder(64);
            formatted.append("## Search Results\n\n");

            for (int i = 0; i < results.size(); i++) {
                Map<String, Object> result = results.get(i);
                String title = (String) result.get("name");
                String url = (String) result.get("url");
                String snippet = (String) result.get("snippet");

                formatted.append("### ").append(i + 1).append(". [").append(title).append("](").append(url).append(")\n");
                if (!Strings.isBlank(snippet)) {
                    formatted.append(snippet).append('\n');
                }
                formatted.append('\n');
            }

            return formatted.toString();

        } catch (Exception e) {
            LOGGER.warn("Failed to parse search results as structured data, returning raw response", e);
            return responseText;
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, WebSearchTool> {
        private String searchApiEndpoint;
        private String apiKey;
        private int maxResults = 10;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder searchApiEndpoint(String searchApiEndpoint) {
            this.searchApiEndpoint = searchApiEndpoint;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder maxResults(int maxResults) {
            this.maxResults = maxResults;
            return this;
        }

        public WebSearchTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(List.of(
                ToolCallParameter.builder()
                    .name("query")
                    .description("The search query to use")
                    .classType(String.class)
                    .required(true)
                    .build(),
                ToolCallParameter.builder()
                    .name("allowed_domains")
                    .description("Only include search results from these domains")
                    .classType(List.class)
                    .itemType(String.class)
                    .required(false)
                    .build(),
                ToolCallParameter.builder()
                    .name("blocked_domains")
                    .description("Never include search results from these domains")
                    .classType(List.class)
                    .itemType(String.class)
                    .required(false)
                    .build()
            ));
            var tool = new WebSearchTool();
            tool.searchApiEndpoint = this.searchApiEndpoint;
            tool.apiKey = this.apiKey;
            tool.maxResults = this.maxResults;
            build(tool);
            return tool;
        }
    }
}
