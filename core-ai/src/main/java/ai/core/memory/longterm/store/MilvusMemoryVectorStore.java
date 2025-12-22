package ai.core.memory.longterm.store;

import ai.core.memory.longterm.LongTermMemoryConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Milvus implementation of MemoryVectorStore.
 * Stores memory embeddings in Milvus for similarity search.
 *
 * @author xander
 */
public class MilvusMemoryVectorStore implements MemoryVectorStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(MilvusMemoryVectorStore.class);
    private static final String ID_FIELD = "id";
    private static final String EMBEDDING_FIELD = "embedding";
    private static final Gson GSON = new Gson();

    private final String host;
    private final int port;
    private final String collectionName;
    private final int embeddingDimension;
    private final Object clientLock = new Object();
    private MilvusClientV2 client;

    public MilvusMemoryVectorStore(LongTermMemoryConfig config) {
        this.host = config.getMilvusHost();
        this.port = config.getMilvusPort();
        this.collectionName = config.getMilvusCollection();
        this.embeddingDimension = config.getEmbeddingDimension();
    }

    public MilvusMemoryVectorStore(String host, int port, String collectionName, int embeddingDimension) {
        this.host = host;
        this.port = port;
        this.collectionName = collectionName;
        this.embeddingDimension = embeddingDimension;
    }

    private MilvusClientV2 getClient() {
        synchronized (clientLock) {
            if (client == null) {
                String uri = String.format("http://%s:%d", host, port);
                var connectConfig = ConnectConfig.builder().uri(uri).build();
                client = new MilvusClientV2(connectConfig);
                ensureCollection();
            }
            return client;
        }
    }

    private void ensureCollection() {
        boolean exists = client.hasCollection(
            HasCollectionReq.builder().collectionName(collectionName).build()
        );
        if (!exists) {
            createCollection();
        }
    }

    private void createCollection() {
        var schema = CreateCollectionReq.CollectionSchema.builder().build();

        schema.addField(AddFieldReq.builder()
            .fieldName(ID_FIELD)
            .dataType(DataType.VarChar)
            .maxLength(36)
            .isPrimaryKey(true)
            .build());

        schema.addField(AddFieldReq.builder()
            .fieldName(EMBEDDING_FIELD)
            .dataType(DataType.FloatVector)
            .dimension(embeddingDimension)
            .build());

        var index = IndexParam.builder()
            .fieldName(EMBEDDING_FIELD)
            .indexName(EMBEDDING_FIELD + "_index")
            .indexType(IndexParam.IndexType.HNSW)
            .metricType(IndexParam.MetricType.COSINE)
            .extraParams(Map.of("M", 16, "efConstruction", 256))
            .build();

        client.createCollection(CreateCollectionReq.builder()
            .collectionName(collectionName)
            .collectionSchema(schema)
            .indexParams(List.of(index))
            .build());

        LOGGER.info("Created Milvus collection: {} with dimension: {}", collectionName, embeddingDimension);
    }

    @Override
    public void save(String id, float[] embedding) {
        var row = new JsonObject();
        row.addProperty(ID_FIELD, id);
        row.add(EMBEDDING_FIELD, GSON.toJsonTree(toList(embedding)));

        getClient().insert(InsertReq.builder()
            .collectionName(collectionName)
            .data(List.of(row))
            .build());
    }

    @Override
    public void saveAll(List<String> ids, List<float[]> embeddings) {
        if (ids.isEmpty()) return;
        if (ids.size() != embeddings.size()) {
            throw new IllegalArgumentException("ids and embeddings must have same size");
        }

        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            var row = new JsonObject();
            row.addProperty(ID_FIELD, ids.get(i));
            row.add(EMBEDDING_FIELD, GSON.toJsonTree(toList(embeddings.get(i))));
            rows.add(row);
        }

        getClient().insert(InsertReq.builder()
            .collectionName(collectionName)
            .data(rows)
            .build());
    }

    @Override
    public void delete(String id) {
        getClient().delete(DeleteReq.builder()
            .collectionName(collectionName)
            .ids(List.of(id))
            .build());
    }

    @Override
    public void deleteAll(List<String> ids) {
        if (ids.isEmpty()) return;

        getClient().delete(DeleteReq.builder()
            .collectionName(collectionName)
            .ids(new ArrayList<>(ids))
            .build());
    }

    @Override
    public List<VectorSearchResult> search(float[] queryEmbedding, int topK) {
        var searchReq = SearchReq.builder()
            .collectionName(collectionName)
            .data(List.of(new FloatVec(toList(queryEmbedding))))
            .annsField(EMBEDDING_FIELD)
            .topK(topK)
            .outputFields(List.of(ID_FIELD))
            .build();

        SearchResp resp = getClient().search(searchReq);
        return extractResults(resp);
    }

    @Override
    public List<VectorSearchResult> search(float[] queryEmbedding, int topK, List<String> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return search(queryEmbedding, topK);
        }

        String filter = buildIdFilter(candidateIds);
        var searchReq = SearchReq.builder()
            .collectionName(collectionName)
            .data(List.of(new FloatVec(toList(queryEmbedding))))
            .annsField(EMBEDDING_FIELD)
            .topK(Math.min(topK, candidateIds.size()))
            .filter(filter)
            .outputFields(List.of(ID_FIELD))
            .build();

        SearchResp resp = getClient().search(searchReq);
        return extractResults(resp);
    }

    @Override
    public int count() {
        // Milvus doesn't have a direct count API in SDK v2
        // Using search with empty query and counting results is not efficient
        // For now, return -1 to indicate unknown
        return -1;
    }

    private List<VectorSearchResult> extractResults(SearchResp resp) {
        List<VectorSearchResult> results = new ArrayList<>();
        var searchResults = resp.getSearchResults();
        if (searchResults.isEmpty()) {
            return results;
        }

        for (var result : searchResults.getFirst()) {
            String id = (String) result.getId();
            double similarity = result.getScore();
            results.add(new VectorSearchResult(id, similarity));
        }
        return results;
    }

    private String buildIdFilter(List<String> candidateIds) {
        var quotedIds = candidateIds.stream()
            .map(id -> "\"" + id + "\"")
            .toList();
        return ID_FIELD + " in [" + String.join(",", quotedIds) + "]";
    }

    private List<Float> toList(float[] array) {
        return IntStream.range(0, array.length)
            .mapToObj(i -> array[i])
            .toList();
    }

    /**
     * Drop the collection. Use with caution.
     */
    public void dropCollection() {
        getClient().dropCollection(
            DropCollectionReq.builder().collectionName(collectionName).build()
        );
        LOGGER.info("Dropped Milvus collection: {}", collectionName);
    }

    /**
     * Close the client connection.
     */
    public void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
