package ai.core.vectorstore.milvus;

import ai.core.document.Document;
import ai.core.document.Embedding;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.vectorstore.VectorStore;
import ai.core.vectorstore.request.ScalarQueryRequest;
import ai.core.vectorstore.spec.CollectionSpec;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.DescribeIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Milvus-backed {@link VectorStore}. The client is created lazily on first use; all methods route through
 * {@link #client()} so the connection setup never diverges from the field access.
 *
 * @author stephen
 */
public class MilvusVectorStore implements VectorStore, AutoCloseable {
    private static final Gson GSON = new Gson();

    private final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);
    private final MilvusConfig config;
    private final Map<String, CollectionInfo> collectionInfos = new HashMap<>();

    private volatile MilvusClientV2 client;

    public MilvusVectorStore(MilvusConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "Milvus";
    }

    @Override
    public List<Document> similaritySearch(SimilaritySearchRequest request) {
        if (request.embedding == null) throw new IllegalArgumentException("embedding must not be null");
        var coll = coll(request.collection);
        var info = collectionInfo(coll);
        var outputFields = new ArrayList<>(request.effectiveOutputFields());
        if (request.includeVector && !outputFields.contains(request.vectorField)) outputFields.add(request.vectorField);
        var req = SearchReq.builder()
                .collectionName(coll)
                .annsField(request.vectorField)
                .data(List.of(new FloatVec(request.embedding.vectors().stream().map(Double::floatValue).toList())))
                .limit(request.topK == null ? 5 : request.topK)
                .outputFields(outputFields)
                .build();
        if (request.filter != null && !request.filter.isBlank()) req.setFilter(request.filter);
        var rsp = client().search(req);
        return rsp.getSearchResults().getFirst().stream()
                .map(result -> toDocument(result.getId(), result.getEntity(), info, outputFields, result.getScore()))
                .filter(document -> passThreshold(document.score, request.threshold, info.metricType()))
                .toList();
    }

    @Override
    public List<Document> query(ScalarQueryRequest request) {
        if (request.filter == null || request.filter.isBlank()) throw new IllegalArgumentException("filter must not be blank");
        var coll = coll(request.collection);
        var info = collectionInfo(coll);
        var outputFields = request.outputFields == null ? new ArrayList<String>() : new ArrayList<>(request.outputFields);
        if (request.includeVector && !outputFields.contains(info.vectorField())) outputFields.add(info.vectorField());
        var req = QueryReq.builder()
                .collectionName(coll)
                .filter(request.filter)
                .offset(request.offset == null ? 0 : request.offset)
                .limit(request.limit == null ? 10 : request.limit)
                .outputFields(outputFields)
                .build();
        var rsp = client().query(req);
        return rsp.getQueryResults().stream()
                .map(result -> toDocument(entityId(result.getEntity(), info), result.getEntity(), info, outputFields, null))
                .toList();
    }

    @Override
    public void add(String collection, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return;
        insert(collection, documents);
    }

    /** Inserts documents and returns the generated/assigned primary keys as strings. */
    public List<String> insert(String collection, List<Document> documents) {
        if (documents == null || documents.isEmpty()) return List.of();
        var coll = coll(collection);
        var info = collectionInfo(coll);
        var rows = documents.stream().map(document -> row(document, info)).toList();
        var rsp = client().insert(InsertReq.builder().collectionName(coll).data(rows).build());
        logger.debug("inserted {} documents into collection {}", rsp.getInsertCnt(), coll);
        return rsp.getPrimaryKeys().stream().map(String::valueOf).toList();
    }

    @Override
    public void deleteByIds(String collection, List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        var coll = coll(collection);
        var info = collectionInfo(coll);
        client().delete(DeleteReq.builder()
                .collectionName(coll)
                .ids(ids.stream().map(id -> toIdValue(id, info.primaryKeyType())).toList())
                .build());
    }

    @Override
    public void deleteByFilter(String collection, String filter) {
        client().delete(DeleteReq.builder().collectionName(coll(collection)).filter(filter).build());
    }

    @Override
    public Optional<Document> getById(String collection, String id) {
        return getByIds(collection, List.of(id)).stream().findFirst();
    }

    @Override
    public List<Document> getByIds(String collection, List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        var coll = coll(collection);
        var info = collectionInfo(coll);
        var outputFields = new ArrayList<String>();
        if (info.fieldNames().contains(info.vectorField())) outputFields.add(info.vectorField());
        if (info.fieldNames().contains(config.contentField)) outputFields.add(config.contentField);
        var rsp = client().query(QueryReq.builder()
                .collectionName(coll)
                .ids(ids.stream().map(id -> toIdValue(id, info.primaryKeyType())).toList())
                .outputFields(outputFields)
                .build());
        return rsp.getQueryResults().stream()
                .map(result -> toDocument(entityId(result.getEntity(), info), result.getEntity(), info, outputFields, null))
                .toList();
    }

    /** Text-based lookup is meaningless for autoID collections; queries by the configured content field instead. */
    @Override
    @Deprecated
    public Optional<Document> get(String text) {
        var coll = coll(null);
        var info = collectionInfo(coll);
        var outputFields = new ArrayList<String>();
        if (info.fieldNames().contains(info.vectorField())) outputFields.add(info.vectorField());
        if (info.fieldNames().contains(config.contentField)) outputFields.add(config.contentField);
        var rsp = client().query(QueryReq.builder()
                .collectionName(coll)
                .filter(config.contentField + " == '" + text.replace("'", "\\'") + "'")
                .outputFields(outputFields)
                .build());
        return rsp.getQueryResults().stream().findFirst()
                .map(result -> toDocument(entityId(result.getEntity(), info), result.getEntity(), info, outputFields, null));
    }

    @Override
    public boolean hasCollection(String collection) {
        return client().hasCollection(HasCollectionReq.builder().collectionName(coll(collection)).build());
    }

    @Override
    public void ensureCollection(CollectionSpec spec) {
        var coll = spec.name();
        if (!client().hasCollection(HasCollectionReq.builder().collectionName(coll).build())) {
            client().createCollection(CreateCollectionReq.builder()
                    .collectionName(coll)
                    .collectionSchema(MilvusSchemaMapper.schema(spec))
                    .indexParams(List.of(MilvusSchemaMapper.index(spec)))
                    .build());
            logger.debug("created collection {}", coll);
        }
        var index = client().describeIndex(DescribeIndexReq.builder().collectionName(coll).build())
                .getIndexDescByFieldName(spec.vectorField());
        if (index == null) {
            client().createIndex(CreateIndexReq.builder()
                    .collectionName(coll)
                    .indexParams(List.of(MilvusSchemaMapper.index(spec)))
                    .build());
        }
        client().loadCollection(LoadCollectionReq.builder().collectionName(coll).build());
    }

    @Override
    public void dropCollection(String collection) {
        var coll = coll(collection);
        collectionInfos.remove(coll);
        if (client().hasCollection(HasCollectionReq.builder().collectionName(coll).build())) {
            client().dropCollection(DropCollectionReq.builder().collectionName(coll).build());
        }
    }

    @Override
    public void loadCollection(String collection) {
        client().loadCollection(LoadCollectionReq.builder().collectionName(coll(collection)).build());
    }

    @Override
    public void close() {
        if (client != null) client.close();
    }

    private MilvusClientV2 client() {
        if (client == null) initClient();
        return client;
    }

    private void initClient() {
        synchronized (this) {
            if (client == null) {
                var builder = ConnectConfig.builder()
                        .uri(config.uri)
                        .token(config.token)
                        .username(config.username)
                        .password(config.password)
                        .connectTimeoutMs(config.connectTimeoutMs);
                if (config.database != null) builder.dbName(config.database);
                client = new MilvusClientV2(builder.build());
            }
        }
    }

    private String coll(String collection) {
        if (collection != null && !collection.isBlank()) return collection;
        if (config.collection != null && !config.collection.isBlank()) return config.collection;
        throw new IllegalArgumentException("collection must be set in the request or configured via sys.milvus.collection");
    }

    private CollectionInfo collectionInfo(String collection) {
        return collectionInfos.computeIfAbsent(collection, c -> {
            var describe = client().describeCollection(DescribeCollectionReq.builder().collectionName(c).build());
            var vectorField = describe.getVectorFieldNames().isEmpty() ? null : describe.getVectorFieldNames().getFirst();
            var pk = describe.getCollectionSchema().getFieldSchemaList().stream()
                    .filter(field -> Boolean.TRUE.equals(field.getIsPrimaryKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("collection " + c + " has no primary key field"));
            var index = client().describeIndex(DescribeIndexReq.builder().collectionName(c).build())
                    .getIndexDescByFieldName(vectorField);
            var metricType = index == null ? IndexParam.MetricType.INVALID : index.getMetricType();
            return new CollectionInfo(vectorField, pk.getName(), pk.getDataType(), Boolean.TRUE.equals(pk.getAutoID()), metricType, Set.copyOf(describe.getFieldNames()));
        });
    }

    JsonObject row(Document document, CollectionInfo info) {
        var row = new JsonObject();
        if (document.embedding != null) {
            row.add(info.vectorField(), GSON.toJsonTree(document.embedding.vectors().stream().map(Double::floatValue).toList()));
        }
        if (document.content != null) row.addProperty(config.contentField, document.content);
        if (document.id != null && !info.autoId()) {
            row.add(info.primaryKeyField(), GSON.toJsonTree(toIdValue(document.id, info.primaryKeyType())));
        }
        if (document.extraField != null) {
            for (var entry : document.extraField.entrySet()) {
                row.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
            }
        }
        return row;
    }

    Document toDocument(Object id, Map<String, Object> entity, CollectionInfo info, List<String> outputFields, Float score) {
        var extraField = extraField(entity, info, outputFields);
        var embedding = embedding(entity, info, outputFields);
        return new Document(id == null ? null : String.valueOf(id), embedding,
                (String) entity.get(config.contentField), extraField, score == null ? null : score.doubleValue());
    }

    private Embedding embedding(Map<String, Object> entity, CollectionInfo info, List<String> outputFields) {
        if (!outputFields.contains(info.vectorField())) return null;
        var value = entity.get(info.vectorField());
        return value == null ? null : Embedding.of(vectorOf(value));
    }

    private Map<String, Object> extraField(Map<String, Object> entity, CollectionInfo info, List<String> outputFields) {
        Map<String, Object> result = null;
        for (var field : outputFields) {
            if (field.equals(info.vectorField()) || field.equals(config.contentField)) continue;
            var value = entity.get(field);
            if (value == null) continue;
            if (result == null) result = new HashMap<>();
            result.put(field, value);
        }
        return result;
    }

    private Object entityId(Map<String, Object> entity, CollectionInfo info) {
        return entity.get(info.primaryKeyField());
    }

    boolean passThreshold(Double score, Double threshold, IndexParam.MetricType metricType) {
        if (score == null || threshold == null) return true;
        return switch (metricType) {
            case L2 -> threshold <= 0 || score <= threshold;
            default -> score >= threshold;   // COSINE / IP: bigger means more similar
        };
    }

    Object toIdValue(String id, DataType type) {
        return type == DataType.Int64 ? Long.valueOf(id) : id;
    }

    @SuppressWarnings("unchecked")
    private List<Float> vectorOf(Object value) {
        return (List<Float>) value;
    }

    record CollectionInfo(String vectorField, String primaryKeyField, DataType primaryKeyType, boolean autoId, IndexParam.MetricType metricType, Set<String> fieldNames) {
    }
}
