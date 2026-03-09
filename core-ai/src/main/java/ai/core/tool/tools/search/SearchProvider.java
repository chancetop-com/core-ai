package ai.core.tool.tools.search;

import java.util.List;

/**
 * @author stephen
 */
public interface SearchProvider {
    List<SearchResult> search(String query, List<String> allowedDomains, List<String> blockedDomains, int maxResults);
}
