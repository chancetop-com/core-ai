package ai.core.tool.tools.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class BingScrapingSearchProvider implements SearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(BingScrapingSearchProvider.class);
    private static final String SEARCH_URL = "https://www.bing.com/search";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @Override
    public List<SearchResult> search(String query, List<String> allowedDomains, List<String> blockedDomains, int maxResults) {
        var modifiedQuery = buildQueryWithDomainFilters(query, allowedDomains, blockedDomains);
        LOGGER.debug("Bing scraping search: {}", modifiedQuery);

        try {
            var encodedQuery = URLEncoder.encode(modifiedQuery, StandardCharsets.UTF_8);
            var url = SEARCH_URL + "?q=" + encodedQuery + "&count=" + maxResults;

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                LOGGER.error("Bing returned status: {}", response.statusCode());
                return List.of();
            }

            return parseResults(response.body(), maxResults);
        } catch (Exception e) {
            LOGGER.error("Bing scraping search failed: {}", e.getMessage(), e);
            throw new RuntimeException("Bing scraping search failed: " + e.getMessage(), e);
        }
    }

    private List<SearchResult> parseResults(String html, int maxResults) {
        var doc = Jsoup.parse(html);
        var results = new ArrayList<SearchResult>();

        // Bing organic results are in <li class="b_algo">
        var resultElements = doc.select("li.b_algo");

        for (Element element : resultElements) {
            if (results.size() >= maxResults) break;

            var linkElement = element.selectFirst("h2 a");
            if (linkElement == null) continue;

            var title = linkElement.text();
            var url = linkElement.attr("href");
            if (url.isEmpty() || !url.startsWith("http")) continue;

            // snippet is in <p> or <div class="b_caption"><p>
            var snippetElement = element.selectFirst("div.b_caption p");
            if (snippetElement == null) {
                snippetElement = element.selectFirst("p");
            }
            var snippet = snippetElement != null ? snippetElement.text() : "";

            results.add(new SearchResult(title, url, snippet));
        }

        LOGGER.debug("Bing scraping returned {} results", results.size());
        return results;
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
