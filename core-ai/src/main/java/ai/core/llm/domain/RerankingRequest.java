package ai.core.llm.domain;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class RerankingRequest {
    public static RerankingRequest of(String query, List<String> candidates) {
        var req = new RerankingRequest();
        req.query = query;
        req.candidates = candidates;
        return req;
    }

    @Property(name = "query")
    public String query;
    @Property(name = "candidates")
    public List<String> candidates;
}
