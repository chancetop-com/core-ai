package ai.core.rag.vectorstore.milvus;

import ai.core.document.Document;
import ai.core.rag.Embedding;
import ai.core.rag.SimilaritySearchRequest;
import ai.core.rag.VectorStore;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import core.framework.inject.Inject;
import core.framework.util.Maps;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
            var document = new Document();
            document.content = (String) v.getEntity().get(request.queryField);
            if (request.extraFields == null || request.extraFields.isEmpty()) return document;
            document.extraField = Maps.newConcurrentHashMap();
            for (var filed : request.extraFields) {
                document.extraField.put(filed, v.getEntity().get(filed));
            }
            return document;
        }).collect(Collectors.toList());
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
