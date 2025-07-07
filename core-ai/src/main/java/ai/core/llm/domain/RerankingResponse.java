package ai.core.llm.domain;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class RerankingResponse {
    @Property(name = "reranked_documents")
    public List<String> rerankedDocuments;
}
