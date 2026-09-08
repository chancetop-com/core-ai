package ai.core.vectorstore;

import ai.core.document.Document;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.vectorstore.request.ScalarQueryRequest;
import ai.core.vectorstore.spec.CollectionSpec;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generic vector collection store.
 * <p>
 * {@code collection} is passed per method instead of being a constructor parameter: most consumers have one
 * collection, but a single connection may serve several (e.g. multi-tenant). A {@code null} collection falls
 * back to the implementation's configured default collection.
 *
 * @author stephen
 */
public interface VectorStore {
    String name();

    // vector search
    List<Document> similaritySearch(SimilaritySearchRequest request);

    default String similaritySearchText(SimilaritySearchRequest request) {
        return similaritySearch(request).stream().map(v -> v.content).distinct().collect(Collectors.joining("\n"));
    }

    // scalar search
    List<Document> query(ScalarQueryRequest request);

    // write
    void add(String collection, List<Document> documents);

    default void add(List<Document> documents) {
        add(null, documents);
    }

    // delete
    void deleteByIds(String collection, List<String> ids);

    void deleteByFilter(String collection, String filter);

    @Deprecated
    default void delete(List<String> texts) {
        deleteByIds(null, texts.stream().map(Document::toId).toList());
    }

    // read
    Optional<Document> getById(String collection, String id);

    List<Document> getByIds(String collection, List<String> ids);

    @Deprecated
    default Optional<Document> get(String text) {
        return getById(null, Document.toId(text));
    }

    @Deprecated
    default List<Document> getAll(List<String> texts) {
        return getByIds(null, texts.stream().map(Document::toId).toList());
    }

    // collection management
    boolean hasCollection(String collection);

    void ensureCollection(CollectionSpec spec);   // idempotent: has -> create -> index -> load

    void dropCollection(String collection);

    default void loadCollection(String collection) {
        // no-op by default; HNSWLib has no load concept
    }
}
