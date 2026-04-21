package ai.core.tool.tools.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author stephen
 */
public class FallbackSearchProvider implements SearchProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackSearchProvider.class);

    private final List<SearchProvider> providers;

    public FallbackSearchProvider(List<SearchProvider> providers) {
        this.providers = providers;
    }

    @Override
    public List<SearchResult> search(String query, List<String> allowedDomains, List<String> blockedDomains, int maxResults) {
        Exception lastException = null;
        for (var provider : providers) {
            try {
                var results = provider.search(query, allowedDomains, blockedDomains, maxResults);
                if (!results.isEmpty()) return results;
                LOGGER.warn("provider {} returned empty results, trying next", provider.getClass().getSimpleName());
            } catch (Exception e) {
                LOGGER.warn("provider {} failed, trying next: {}", provider.getClass().getSimpleName(), e.getMessage());
                lastException = e;
            }
        }
        if (lastException != null) throw new RuntimeException("all search providers failed", lastException);
        return List.of();
    }
}
