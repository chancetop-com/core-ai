package ai.core.vectorstore.hnswlib;

import ai.core.document.Document;
import ai.core.document.Embedding;
import ai.core.rag.DistanceMetricType;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.vectorstore.VectorStore;
import ai.core.vectorstore.request.ScalarQueryRequest;
import ai.core.vectorstore.spec.CollectionSpec;
import com.github.jelmerk.hnswlib.core.DistanceFunction;
import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Index;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import core.framework.json.JSON;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File-backed HNSWLib {@link VectorStore}. The {@code collection} argument is ignored by all methods — this
 * implementation serves exactly one index per file path.
 *
 * @author stephen
 */
public class HnswLibVectorStore implements VectorStore {
    public static DistanceFunction<float[], Float> mapToFunction(DistanceMetricType metricType) {
        return switch (metricType) {
            case EUCLIDEAN -> DistanceFunctions.FLOAT_EUCLIDEAN_DISTANCE;
            case MANHATTAN -> DistanceFunctions.FLOAT_MANHATTAN_DISTANCE;
            case PRODUCT -> DistanceFunctions.FLOAT_INNER_PRODUCT;
            case CANBERRA -> DistanceFunctions.FLOAT_CANBERRA_DISTANCE;
            case BRAY_CURTIS -> DistanceFunctions.FLOAT_BRAY_CURTIS_DISTANCE;
            case CORRELATION -> DistanceFunctions.FLOAT_CORRELATION_DISTANCE;
            default -> DistanceFunctions.FLOAT_COSINE_DISTANCE;
        };
    }

    public static void build(HnswConfig config, List<Document> documents) throws IOException, InterruptedException {
        if (config.path().isEmpty() || documents.isEmpty()) throw new IllegalArgumentException("Path or documents is empty");
        HnswIndex<String, float[], HnswDocument, Float> index = HnswIndex.newBuilder(config.dimension(), mapToFunction(config.metricType()), config.maxItemCount())
                .withEf(config.efConstruction())
                .withM(config.m())
                .withRemoveEnabled()
                .build();
        var docs = documents.stream().map(HnswLibVectorStore::fromDocument).toList();
        index.addAll(docs);
        index.save(Paths.get(config.path()));
    }

    private static HnswDocument fromDocument(Document document) {
        return new HnswDocument(document.id, document.embedding.toFloatArray(), document.content, JSON.toJSON(document.extraField));
    }

    public static Index<String, float[], HnswDocument, Float> load(String path) throws IOException {
        return HnswIndex.load(Paths.get(path));
    }

    private final HnswConfig config;
    private Index<String, float[], HnswDocument, Float> index;

    public HnswLibVectorStore(HnswConfig config) {
        this.config = config;
    }

    public void init() {
        try {
            this.index = load(config.path());
        } catch (IOException e) {
            throw new RuntimeException("Load HnswLib failed: " + config.path(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Document> similaritySearch(SimilaritySearchRequest request) {
        if (this.index == null) init();
        var rsp = this.index.findNearest(request.embedding.toFloatArray(), request.topK);
        return rsp.stream()
                .filter(v -> v.distance() <= request.threshold)
                .sorted(Comparator.comparingDouble(SearchResult::distance))
                .map(v -> new Document(
                        v.item().id(),
                        Embedding.of(v.item().vector()),
                        v.item().content(),
                        (Map<String, Object>) JSON.fromJSON(Map.class, v.item().extraField()),
                        v.distance().doubleValue())).toList();
    }

    @Override
    public List<Document> query(ScalarQueryRequest request) {
        throw new UnsupportedOperationException("HNSWLib does not support scalar queries");
    }

    @Override
    public void add(String collection, List<Document> documents) {
        try {
            this.index.addAll(documents.stream().map(HnswLibVectorStore::fromDocument).toList());
            save();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("HnswLib add document failed: ", e);
        }
    }

    @Override
    public void deleteByIds(String collection, List<String> ids) {
        ids.forEach(id -> this.index.remove(id, 0));
    }

    @Override
    public void deleteByFilter(String collection, String filter) {
        throw new UnsupportedOperationException("HNSWLib does not support filtering");
    }

    @Override
    public Optional<Document> getById(String collection, String id) {
        return this.index.get(id).map(this::toDocument);
    }

    @Override
    public List<Document> getByIds(String collection, List<String> ids) {
        return ids.stream().map(this.index::get).filter(Optional::isPresent).map(Optional::get).map(this::toDocument).toList();
    }

    @Override
    public boolean hasCollection(String collection) {
        return Files.exists(Paths.get(config.path()));
    }

    @Override
    public void ensureCollection(CollectionSpec spec) {
        if (hasCollection(spec.name())) return;
        try {
            HnswIndex<String, float[], HnswDocument, Float> created = HnswIndex.newBuilder(
                            spec.dimension(), mapToFunction(spec.metricType()), Math.max(config.maxItemCount(), spec.dimension()))
                    .withEf(config.efConstruction())
                    .withM(config.m())
                    .withRemoveEnabled()
                    .build();
            created.save(Paths.get(config.path()));
            index = created;
        } catch (IOException e) {
            throw new RuntimeException("HnswLib ensure collection failed: " + config.path(), e);
        }
    }

    @Override
    public void dropCollection(String collection) {
        try {
            Files.deleteIfExists(Paths.get(config.path()));
        } catch (IOException e) {
            throw new RuntimeException("HnswLib drop collection failed: " + config.path(), e);
        }
        index = null;
    }

    @Override
    public String name() {
        return "HNSWLib";
    }

    public void save() throws IOException, InterruptedException {
        index.save(Paths.get(config.path()));
    }

    @SuppressWarnings("unchecked")
    private Document toDocument(HnswDocument hnswDocument) {
        return new Document(hnswDocument.id(), Embedding.of(hnswDocument.vector()), hnswDocument.content(), (Map<String, Object>) JSON.fromJSON(Map.class, hnswDocument.extraField()));
    }
}
