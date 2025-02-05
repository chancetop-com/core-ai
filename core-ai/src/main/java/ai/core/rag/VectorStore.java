package ai.core.rag;

import ai.core.document.Document;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public interface VectorStore {
    List<Document> similaritySearch(SimilaritySearchRequest request);

    default String similaritySearchText(SimilaritySearchRequest request) {
        return similaritySearch(request).stream().map(v -> v.content).distinct().collect(Collectors.joining("\n"));
    }
}
