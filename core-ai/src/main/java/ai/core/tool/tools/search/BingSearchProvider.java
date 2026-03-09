package ai.core.tool.tools.search;

import ai.core.internal.http.PatchedHTTPClientBuilder;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class BingSearchProvider implements SearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(BingSearchProvider.class);
    private static final String DEFAULT_ENDPOINT = "https://api.bing.microsoft.com/v7.0/search";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    private final HTTPClient client = new PatchedHTTPClientBuilder().build();
    private final String endpoint;
    private final String apiKey;

    public BingSearchProvider(String endpoint, String apiKey) {
        this.endpoint = Strings.isBlank(endpoint) ? DEFAULT_ENDPOINT : endpoint;
        this.apiKey = apiKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SearchResult> search(String query, List<String> allowedDomains, List<String> blockedDomains, int maxResults) {
        var modifiedQuery = buildQueryWithDomainFilters(query, allowedDomains, blockedDomains);
        LOGGER.debug("Bing search: {}", modifiedQuery);

        var encodedQuery = URLEncoder.encode(modifiedQuery, StandardCharsets.UTF_8);
        var url = buildSearchUrl(encodedQuery, maxResults);

        var request = new HTTPRequest(HTTPMethod.GET, url);
        request.headers.put(HTTPHeaders.USER_AGENT, USER_AGENT);
        if (!Strings.isBlank(apiKey)) {
            request.headers.put("Authorization", "Bearer " + apiKey);
        }

        var response = client.execute(request);
        if (response.statusCode >= 400) {
            throw new RuntimeException("Bing search failed with status " + response.statusCode + ": " + response.text());
        }

        return parseResults(response.text());
    }

    @SuppressWarnings("unchecked")
    private List<SearchResult> parseResults(String responseText) {
        Map<String, Object> response = JSON.fromJSON(Map.class, responseText);
        var webPages = (Map<String, Object>) response.get("webPages");
        if (webPages == null) return List.of();

        var values = (List<Map<String, Object>>) webPages.get("value");
        if (values == null) return List.of();

        var results = new ArrayList<SearchResult>();
        for (var value : values) {
            var title = (String) value.get("name");
            var url = (String) value.get("url");
            var snippet = (String) value.get("snippet");
            results.add(new SearchResult(title, url, snippet != null ? snippet : ""));
        }
        return results;
    }

    private String buildSearchUrl(String encodedQuery, int maxResults) {
        if (endpoint.contains("?")) {
            return endpoint + "&q=" + encodedQuery + "&count=" + maxResults;
        }
        return endpoint + "?q=" + encodedQuery + "&count=" + maxResults;
    }

    private String buildQueryWithDomainFilters(String query, List<String> allowedDomains, List<String> blockedDomains) {
        var sb = new StringBuilder(query);

        if (allowedDomains != null && !allowedDomains.isEmpty()) {
            sb.append(" (");
            for (int i = 0; i < allowedDomains.size(); i++) {
                if (i > 0) sb.append(" OR ");
                sb.append("site:").append(allowedDomains.get(i));
            }
            sb.append(')');
        }

        if (blockedDomains != null && !blockedDomains.isEmpty()) {
            for (String domain : blockedDomains) {
                sb.append(" -site:").append(domain);
            }
        }

        return sb.toString();
    }
}
