package ai.core.memory.store;

import ai.core.document.Embedding;
import ai.core.llm.LLMProvider;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.memory.LongTermMemory;
import ai.core.memory.model.MemoryEntry;
import ai.core.vectorstore.vectorstores.milvus.MilvusConfig;
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
import io.milvus.v2.service.vector.request.GetReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Milvus-based implementation of LongTermMemory.
 * Provides persistent vector storage with efficient similarity search.
 *
 * @author xander
 */
public class MilvusMemoryStore implements LongTermMemory {
    private static final Logger LOGGER = LoggerFactory.getLogger(MilvusMemoryStore.class);
    private static final String DEFAULT_COLLECTION = "long_term_memory";
    private static final int DEFAULT_DIMENSION = 1536;
    private static final int MAX_CONTENT_LENGTH = 65535;

    // Field names
    private static final String FIELD_ID = "id";
    private static final String FIELD_USER_ID = "user_id";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_VECTOR = "vector";
    private static final String FIELD_CREATED_AT = "created_at";
    private static final String FIELD_METADATA = "metadata";

    private final MilvusConfig config;
    private final LLMProvider llmProvider;
    private final String collectionName;
    private final int dimension;
    private final Gson gson = new Gson();

    private MilvusClientV2 client;
    private boolean initialized = false;

    public MilvusMemoryStore(MilvusConfig config, LLMProvider llmProvider) {
        this(config, llmProvider, DEFAULT_COLLECTION, DEFAULT_DIMENSION);
    }

    public MilvusMemoryStore(MilvusConfig config, LLMProvider llmProvider, String collectionName) {
        this(config, llmProvider, collectionName, DEFAULT_DIMENSION);
    }

    public MilvusMemoryStore(MilvusConfig config, LLMProvider llmProvider, String collectionName, int dimension) {
        this.config = config;
        this.llmProvider = llmProvider;
        this.collectionName = collectionName;
        this.dimension = dimension;
    }

    private void ensureInitialized() {
        if (!initialized) {
            init();
        }
    }

    private void init() {
        var connectConfig = ConnectConfig.builder()
            .uri(config.getUri())
            .token(config.getToken())
            .username(config.getUsername())
            .password(config.getPassword())
            .dbName(config.getDatabase())
            .build();

        this.client = new MilvusClientV2(connectConfig);

        if (!hasCollection()) {
            createCollection();
        }

        initialized = true;
        LOGGER.info("MilvusMemoryStore initialized with collection: {}", collectionName);
    }

    private boolean hasCollection() {
        return client.hasCollection(HasCollectionReq.builder()
            .collectionName(collectionName)
            .build());
    }

    private void createCollection() {
        var schema = CreateCollectionReq.CollectionSchema.builder().build();

        // Primary key
        schema.addField(AddFieldReq.builder()
            .fieldName(FIELD_ID)
            .dataType(DataType.VarChar)
            .maxLength(64)
            .isPrimaryKey(true)
            .build());

        // User ID for filtering
        schema.addField(AddFieldReq.builder()
            .fieldName(FIELD_USER_ID)
            .dataType(DataType.VarChar)
            .maxLength(128)
            .build());

        // Memory content
        schema.addField(AddFieldReq.builder()
            .fieldName(FIELD_CONTENT)
            .dataType(DataType.VarChar)
            .maxLength(MAX_CONTENT_LENGTH)
            .build());

        // Vector embedding
        schema.addField(AddFieldReq.builder()
            .fieldName(FIELD_VECTOR)
            .dataType(DataType.FloatVector)
            .dimension(dimension)
            .build());

        // Timestamp
        schema.addField(AddFieldReq.builder()
            .fieldName(FIELD_CREATED_AT)
            .dataType(DataType.Int64)
            .build());

        // Metadata JSON
        schema.addField(AddFieldReq.builder()
            .fieldName(FIELD_METADATA)
            .dataType(DataType.VarChar)
            .maxLength(4096)
            .build());

        // Create index on vector field
        var index = IndexParam.builder()
            .fieldName(FIELD_VECTOR)
            .indexName(FIELD_VECTOR + "_index")
            .indexType(IndexParam.IndexType.IVF_FLAT)
            .metricType(IndexParam.MetricType.COSINE)
            .extraParams(Map.of("nlist", 256))
            .build();

        client.createCollection(CreateCollectionReq.builder()
            .collectionName(collectionName)
            .collectionSchema(schema)
            .indexParams(List.of(index))
            .build());

        LOGGER.info("Created Milvus collection: {}", collectionName);
    }

    @Override
    public void add(MemoryEntry entry) {
        if (entry == null || entry.getContent() == null) {
            return;
        }
        ensureInitialized();

        // Generate embedding if needed
        Embedding embedding = entry.getEmbedding();
        if (embedding == null && llmProvider != null) {
            embedding = generateEmbedding(entry.getContent());
            entry.setEmbedding(embedding);
        }

        if (embedding == null) {
            LOGGER.warn("Cannot add memory without embedding: {}", entry.getId());
            return;
        }

        var row = new JsonObject();
        row.addProperty(FIELD_ID, entry.getId());
        row.addProperty(FIELD_USER_ID, entry.getUserId() != null ? entry.getUserId() : "");
        row.addProperty(FIELD_CONTENT, entry.getContent());
        row.add(FIELD_VECTOR, gson.toJsonTree(toFloatList(embedding.vectors())));
        row.addProperty(FIELD_CREATED_AT, entry.getCreatedAt().toEpochMilli());
        row.addProperty(FIELD_METADATA, gson.toJson(entry.getMetadata()));

        client.insert(InsertReq.builder()
            .collectionName(collectionName)
            .data(List.of(row))
            .build());

        LOGGER.debug("Added memory to Milvus: {}", entry.getId());
    }

    @Override
    public void update(String memoryId, MemoryEntry entry) {
        if (memoryId == null || entry == null) {
            return;
        }
        // Milvus doesn't support direct update, so delete and re-insert
        delete(memoryId);
        entry.setId(memoryId);
        add(entry);
    }

    @Override
    public void delete(String memoryId) {
        if (memoryId == null) {
            return;
        }
        ensureInitialized();

        client.delete(DeleteReq.builder()
            .collectionName(collectionName)
            .filter(FIELD_ID + " == \"" + memoryId + "\"")
            .build());

        LOGGER.debug("Deleted memory from Milvus: {}", memoryId);
    }

    @Override
    public Optional<MemoryEntry> getById(String memoryId) {
        if (memoryId == null) {
            return Optional.empty();
        }
        ensureInitialized();

        var result = client.get(GetReq.builder()
            .collectionName(collectionName)
            .ids(List.of(memoryId))
            .outputFields(List.of(FIELD_ID, FIELD_USER_ID, FIELD_CONTENT, FIELD_CREATED_AT, FIELD_METADATA))
            .build());

        if (result.getGetResults().isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(rowToEntry(result.getGetResults().getFirst().getEntity()));
    }

    @Override
    public List<MemoryEntry> getByUserId(String userId, int limit) {
        ensureInitialized();

        String filter = userId != null ? FIELD_USER_ID + " == \"" + userId + "\"" : "";

        var result = client.query(QueryReq.builder()
            .collectionName(collectionName)
            .filter(filter.isEmpty() ? null : filter)
            .outputFields(List.of(FIELD_ID, FIELD_USER_ID, FIELD_CONTENT, FIELD_CREATED_AT, FIELD_METADATA))
            .limit(limit)
            .build());

        return result.getQueryResults().stream()
            .map(r -> rowToEntry(r.getEntity()))
            .toList();
    }

    @Override
    public List<MemoryEntry> search(String query, String userId, int topK) {
        if (query == null || query.isBlank()) {
            return getByUserId(userId, topK);
        }
        ensureInitialized();

        var embedding = generateEmbedding(query);
        if (embedding == null) {
            // Fallback to simple query if no embedding
            return getByUserId(userId, topK);
        }

        return findSimilar(embedding, userId, topK, 0.0);
    }

    @Override
    public List<MemoryEntry> findSimilar(Embedding embedding, String userId, int topK, double threshold) {
        if (embedding == null || embedding.vectors() == null) {
            return List.of();
        }
        ensureInitialized();

        String filter = userId != null ? FIELD_USER_ID + " == \"" + userId + "\"" : null;

        var searchReq = SearchReq.builder()
            .collectionName(collectionName)
            .data(List.of(new FloatVec(toFloatList(embedding.vectors()))))
            .topK(topK)
            .annsField(FIELD_VECTOR)
            .outputFields(List.of(FIELD_ID, FIELD_USER_ID, FIELD_CONTENT, FIELD_CREATED_AT, FIELD_METADATA));

        if (filter != null) {
            searchReq.filter(filter);
        }

        var result = client.search(searchReq.build());

        if (result.getSearchResults().isEmpty()) {
            return List.of();
        }

        return result.getSearchResults().getFirst().stream()
            .filter(r -> r.getScore() >= threshold)
            .map(r -> rowToEntry(r.getEntity()))
            .toList();
    }

    @Override
    public List<MemoryEntry> getAll() {
        ensureInitialized();

        var result = client.query(QueryReq.builder()
            .collectionName(collectionName)
            .outputFields(List.of(FIELD_ID, FIELD_USER_ID, FIELD_CONTENT, FIELD_CREATED_AT, FIELD_METADATA))
            .limit(10000)
            .build());

        return result.getQueryResults().stream()
            .map(r -> rowToEntry(r.getEntity()))
            .toList();
    }

    @Override
    public int size() {
        ensureInitialized();

        var result = client.query(QueryReq.builder()
            .collectionName(collectionName)
            .outputFields(List.of(FIELD_ID))
            .limit(100000)
            .build());

        return result.getQueryResults().size();
    }

    @Override
    public void clear() {
        ensureInitialized();

        client.dropCollection(DropCollectionReq.builder()
            .collectionName(collectionName)
            .build());

        createCollection();
        LOGGER.info("Cleared all memories from Milvus collection: {}", collectionName);
    }

    private MemoryEntry rowToEntry(Map<String, Object> entity) {
        String id = (String) entity.get(FIELD_ID);
        String userId = (String) entity.get(FIELD_USER_ID);
        String content = (String) entity.get(FIELD_CONTENT);
        Long createdAtMs = (Long) entity.get(FIELD_CREATED_AT);
        String metadataJson = (String) entity.get(FIELD_METADATA);

        Instant createdAt = createdAtMs != null ? Instant.ofEpochMilli(createdAtMs) : Instant.now();
        Map<String, Object> metadata = new HashMap<>();
        if (metadataJson != null && !metadataJson.isBlank()) {
            try {
                metadata = gson.fromJson(metadataJson, Map.class);
            } catch (Exception e) {
                LOGGER.debug("Failed to parse metadata: {}", e.getMessage());
            }
        }

        return new MemoryEntry(id, userId, content, null, metadata, createdAt);
    }

    private Embedding generateEmbedding(String content) {
        if (llmProvider == null || content == null || content.isBlank()) {
            return null;
        }
        try {
            var request = new EmbeddingRequest(List.of(content));
            var response = llmProvider.embeddings(request);
            if (response != null && response.embeddings != null && !response.embeddings.isEmpty()) {
                return response.embeddings.getFirst().embedding;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to generate embedding: {}", e.getMessage());
        }
        return null;
    }

    private List<Float> toFloatList(List<Double> doubles) {
        List<Float> floats = new ArrayList<>(doubles.size());
        for (Double d : doubles) {
            floats.add(d.floatValue());
        }
        return floats;
    }

    /**
     * Close the Milvus client connection.
     */
    public void close() {
        if (client != null) {
            client.close();
            initialized = false;
        }
    }
}
