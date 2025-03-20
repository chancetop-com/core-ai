package ai.core.rag.vectorstore.hnswlib;

import ai.core.document.Document;
import ai.core.document.Embedding;
import ai.core.rag.DistanceMetricType;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.rag.VectorStore;
import com.github.jelmerk.hnswlib.core.DistanceFunction;
import com.github.jelmerk.hnswlib.core.DistanceFunctions;
import com.github.jelmerk.hnswlib.core.Index;
import com.github.jelmerk.hnswlib.core.SearchResult;
import com.github.jelmerk.hnswlib.core.hnsw.HnswIndex;
import core.framework.web.exception.NotFoundException;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
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
        if (config.path().isEmpty() || documents.isEmpty()) throw new NotFoundException("Path or documents is empty");
        HnswIndex<String, float[], HnswDocument, Float> index = HnswIndex.newBuilder(config.dimension(), mapToFunction(config.metricType()), config.maxItemCount())
                .withEf(config.efConstruction())
                .withM(config.m())
                .build();
        var docs = documents.stream().map(HnswLibVectorStore::fromDocument).toList();
        index.addAll(docs);
        index.save(Paths.get(config.path()));
    }

    private static HnswDocument fromDocument(Document document) {
        return new HnswDocument(document.id, document.embedding.toFloatArray(), document.content, document.extraField);
    }

    public static Index<String, float[], HnswDocument, Float> load(String path) throws IOException {
        return HnswIndex.load(Paths.get(path));
    }

    private final HnswConfig config;
    private final Index<String, float[], HnswDocument, Float> index;

    public HnswLibVectorStore(HnswConfig config) {
        this.config = config;
        try {
            this.index = load(config.path());
        } catch (IOException e) {
            throw new RuntimeException("Load HnswLib failed: " + config.path(), e);
        }
    }

    @Override
    public List<Document> similaritySearch(SimilaritySearchRequest request) {
        if (this.index == null) throw new NotFoundException("Index not loaded, please loadDocuments/loadFile first");
        var rsp = this.index.findNearest(request.embedding.toFloatArray(), request.topK);
        return rsp.stream()
                .sorted(Comparator.comparingDouble(SearchResult::distance))
                .map(v -> new Document(
                        v.item().id(),
                        Embedding.of(v.item().vector()),
                        v.item().content(),
                        v.item().extraField())).toList();
    }

    @Override
    public Optional<Document> get(String text) {
        return this.index.get(Document.toId(text)).map(this::toDocument);
    }

    @Override
    public List<Document> getAll(List<String> text) {
        return text.stream().map(this::get).filter(Optional::isPresent).map(Optional::get).toList();
    }

    @Override
    public void add(List<Document> documents) {
        try {
            this.index.addAll(documents.stream().map(HnswLibVectorStore::fromDocument).toList());
            save();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("HnswLib add document failed: ", e);
        }
    }

    @Override
    public void delete(List<String> texts) {
        texts.forEach(this::delete);
    }

    public void delete(String text) {
        this.index.remove(Document.toId(text), 0);
    }

    public void save() throws IOException, InterruptedException {
        index.save(Paths.get(config.path()));
    }

    private Document toDocument(HnswDocument hnswDocument) {
        return new Document(hnswDocument.id(), Embedding.of(hnswDocument.vector()), hnswDocument.content(), hnswDocument.extraField());
    }
}
