package ai.core.rag.vectorstore.milvus;

import ai.core.document.Document;
import ai.core.document.Embedding;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.rag.VectorStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import core.framework.inject.Inject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
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
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class MilvusVectorStore implements VectorStore {
    private final Logger logger = LoggerFactory.getLogger(MilvusVectorStore.class);
    @Inject
    MilvusClientV2 milvusClientV2;

    @Override
    public List<Document> similaritySearch(SimilaritySearchRequest request) {
        List<String> outputFields;
        if (request.extraFields != null && !request.extraFields.isEmpty()) {
            outputFields = new ArrayList<>(request.extraFields);
            outputFields.add(request.queryField);
        } else {
            outputFields = List.of(request.queryField);
        }
        var req = SearchReq.builder()
                .data(List.of(new FloatVec(request.embedding.vectors().stream().map(Double::floatValue).toList())))
                .collectionName(request.collection)
                .topK(request.topK)
                .outputFields(outputFields)
                .annsField(request.vectorField).build();
        var rsp = milvusClientV2.search(req);
        return rsp.getSearchResults().getFirst().stream().map(v -> {
            var extraField = request.extraFields == null || request.extraFields.isEmpty() ? null : new HashMap<String, Object>();
            if (extraField != null) {
                for (var filed : request.extraFields) {
                    extraField.put(filed, v.getEntity().get(filed));
                }
            }
            return new Document(
                    (String) v.getId(),
                    Embedding.of((float[]) v.getEntity().get(request.vectorField)),
                    (String) v.getEntity().get(request.queryField),
                    extraField);
        }).collect(Collectors.toList());
    }

    @Override
    public Optional<Document> get(String text) {
        return Optional.of(milvusClientV2.query(QueryReq.builder()
                .filter("query = " + text)
                .outputFields(List.of("id", "query", "vector"))
                .build()).getQueryResults().getFirst()).map(v -> {
                    var entity = v.getEntity();
                    return new Document((String) entity.get("id"), Embedding.of((float[]) entity.get("vector")), (String) entity.get("query"), null);
                });
    }

    @Override
    public List<Document> getAll(List<String> text) {
        return List.of();
    }

    @Override
    public void add(List<Document> documents) {

    }

    @Override
    public void delete(List<String> texts) {

    }

    public boolean hasCollection(String collection) {
        return milvusClientV2.hasCollection(HasCollectionReq.builder().collectionName(collection).build());
    }

    public void createWikiCollection(String collection) {
        var req = new SimilaritySearchRequest();
        var collectionSchema = CreateCollectionReq.CollectionSchema.builder()
                .build();
        collectionSchema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.TRUE)
                .build());
        collectionSchema.addField(AddFieldReq.builder()
                .fieldName(req.vectorField)
                .dataType(DataType.FloatVector)
                .dimension(req.dimension)
                .build());
        collectionSchema.addField(AddFieldReq.builder()
                .fieldName(req.queryField)
                .dataType(DataType.VarChar)
                .maxLength(req.trunkSize)
                .build());
        var index = IndexParam.builder()
                .fieldName(req.vectorField)
                .indexName(req.vectorField + "Index")
                .extraParams(Map.of("nlist", 256))
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE).build();
        milvusClientV2.createCollection(CreateCollectionReq.builder().collectionName(collection).collectionSchema(collectionSchema).indexParams(List.of(index)).build());
    }

    public void insertWiki(String collection, String query, Embedding embedding) {
        var gson = new Gson();
        var row = new JsonObject();
        row.addProperty("query", query);
        row.add("vector", gson.toJsonTree(embedding.vectors()));
        var rsp = milvusClientV2.insert(InsertReq.builder()
                .collectionName(collection)
                .data(List.of(row)).build());
        logger.info("insert wiki: {}", rsp.getPrimaryKeys().getFirst());
    }

    public void createImageCaptionCollection(String collection) {
        var req = new SimilaritySearchRequest();
        var collectionSchema = CreateCollectionReq.CollectionSchema.builder()
                .build();
        collectionSchema.addField(AddFieldReq.builder()
                .fieldName("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.TRUE)
                .build());
        collectionSchema.addField(AddFieldReq.builder()
                .fieldName(req.vectorField)
                .dataType(DataType.FloatVector)
                .dimension(req.dimension)
                .build());
        collectionSchema.addField(AddFieldReq.builder()
                .fieldName("url")
                .dataType(DataType.VarChar)
                .maxLength(500)
                .build());
        collectionSchema.addField(AddFieldReq.builder()
                .fieldName(req.queryField)
                .dataType(DataType.VarChar)
                .maxLength(req.trunkSize)
                .build());
        var index = IndexParam.builder()
                .fieldName(req.vectorField)
                .indexName(req.vectorField + "Index")
                .extraParams(Map.of("nlist", 256))
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE).build();
        milvusClientV2.createCollection(CreateCollectionReq.builder().collectionName(collection).collectionSchema(collectionSchema).indexParams(List.of(index)).build());
    }

    public void insertImageCaption(String collection, String url, String query, Embedding embedding) {
        var gson = new Gson();
        var row = new JsonObject();
        row.addProperty("url", url);
        row.addProperty("query", query);
        row.add("vector", gson.toJsonTree(embedding.vectors()));
        var rsp = milvusClientV2.insert(InsertReq.builder()
                .collectionName(collection)
                .data(List.of(row)).build());
        logger.info("insert image caption: {}", rsp.getPrimaryKeys().getFirst());
    }
}
