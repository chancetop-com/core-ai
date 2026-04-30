package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.search.BingScrapingSearchProvider;
import ai.core.tool.tools.search.BingSearchProvider;
import ai.core.tool.tools.search.ExaSearchProvider;
import ai.core.tool.tools.search.FallbackSearchProvider;
import ai.core.tool.tools.search.SearchProvider;
import ai.core.tool.tools.search.SearchResult;
import core.framework.util.Strings;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class WebSearchTool extends ToolCall {
    public static final String TOOL_NAME = "web_search";

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

    private SearchProvider searchProvider;
    private int maxResults = 10;

    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResult execute(String text) {
        var startTime = System.currentTimeMillis();
        try {
            var argsMap = parseArguments(text);
            var query = getStringValue(argsMap, "query");
            var allowedDomains = (List<String>) argsMap.get("allowed_domains");
            var blockedDomains = (List<String>) argsMap.get("blocked_domains");
            var numResults = argsMap.get("num_results") instanceof Number n
                    ? Math.min(n.intValue(), maxResults)
                    : maxResults;

            if (Strings.isBlank(query)) {
                return ToolCallResult.failed("Error: query parameter is required")
                        .withDuration(System.currentTimeMillis() - startTime);
            }

            var results = searchProvider.search(query, allowedDomains, blockedDomains, numResults);
            var formatted = formatSearchResults(results);

            return ToolCallResult.completed(formatted)
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("query", query)
                    .withStats("provider", searchProvider.getClass().getSimpleName())
                    .withStats("results", results.size());
        } catch (Exception e) {
            var error = "Failed to execute web search: " + e.getMessage();
            return ToolCallResult.failed(error, e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String formatSearchResults(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No search results found.";
        }

        // Exa MCP returns a single pre-formatted text block (no URL)
        if (results.size() == 1 && Strings.isBlank(results.getFirst().url())) {
            return results.getFirst().snippet();
        }

        var sb = new StringBuilder(64);
        sb.append("## Search Results\n\n");

        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i);
            sb.append("### ").append(i + 1).append(". [").append(result.title()).append("](").append(result.url()).append(")\n");
            if (!Strings.isBlank(result.snippet())) {
                sb.append(result.snippet()).append('\n');
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    public static class Builder extends ToolCall.Builder<Builder, WebSearchTool> {
        private String searchApiEndpoint;
        private String apiKey;
        private int maxResults = 10;
        private SearchProvider provider;

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

        public Builder provider(SearchProvider provider) {
            this.provider = provider;
            return this;
        }

        public WebSearchTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "query", "The search query to use").required(),
                    ToolCallParameters.ParamSpec.of(Integer.class, "num_results", "Number of search results to return (default: 10)"),
                    ToolCallParameters.ParamSpec.of(List.class, "allowed_domains", "Only include search results from these domains"),
                    ToolCallParameters.ParamSpec.of(List.class, "blocked_domains", "Never include search results from these domains")
            ));
            var tool = new WebSearchTool();
            tool.maxResults = this.maxResults;
            if (this.provider != null) {
                tool.searchProvider = this.provider;
            } else if (!Strings.isBlank(this.apiKey)) {
                tool.searchProvider = new BingSearchProvider(this.searchApiEndpoint, this.apiKey);
            } else {
                tool.searchProvider = new FallbackSearchProvider(List.of(
                        new ExaSearchProvider(),
                        new BingScrapingSearchProvider()
                ));
            }
            build(tool);
            return tool;
        }
    }
}
