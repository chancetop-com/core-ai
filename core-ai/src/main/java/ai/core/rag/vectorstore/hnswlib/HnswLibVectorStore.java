package ai.core.rag.vectorstore.hnswlib;

import ai.core.document.Document;
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

/**
 * @author stephen
 */
public class HnswLibVectorStore implements VectorStore {
    private Index<String, float[], HnswDocument, Float> index;

    @Override
    public List<Document> similaritySearch(SimilaritySearchRequest request) {
        if (this.index == null) throw new NotFoundException("Index not loaded");
        var rsp = this.index.findNearest(request.embedding.toFloatArray(), request.topK);
        return rsp.stream()
                .sorted(Comparator.comparingDouble(SearchResult::distance))
                .map(v -> new Document(v.item().id, v.item().vector)).toList();
    }

    public void load(String path) throws IOException {
        this.index = HnswIndex.load(Paths.get(path));
    }

    public void save(String path, List<Document> documents, HnswConfig config) throws IOException, InterruptedException {
        if (path.isEmpty() || documents.isEmpty()) return;
        HnswIndex<String, float[], HnswDocument, Float> index = HnswIndex.newBuilder(config.dimensions(), mapToFunction(config.distanceFunction()), config.maxItemCount())
                .withEf(config.efConstruction())
                .withM(config.m())
                .build();
        var docs = documents.stream().map(v -> new HnswDocument(v.id, v.embedding.toFloatArray(), config.dimensions())).toList();
        index.addAll(docs);
        index.save(Paths.get(path));
    }

    private DistanceFunction<float[], Float> mapToFunction(String s) {
        return switch (s) {
            case "euclidean" -> DistanceFunctions.FLOAT_EUCLIDEAN_DISTANCE;
            case "manhattan" -> DistanceFunctions.FLOAT_MANHATTAN_DISTANCE;
            case "product" -> DistanceFunctions.FLOAT_INNER_PRODUCT;
            case "canberra" -> DistanceFunctions.FLOAT_CANBERRA_DISTANCE;
            case "bray-curtis" -> DistanceFunctions.FLOAT_BRAY_CURTIS_DISTANCE;
            case "correlation" -> DistanceFunctions.FLOAT_CORRELATION_DISTANCE;
            default -> DistanceFunctions.FLOAT_COSINE_DISTANCE;
        };
    }
}
