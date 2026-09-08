package ai.core.rag;

import ai.core.document.Embedding;
import ai.core.rag.filter.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class SimilaritySearchRequest {
    public static Builder builder() {
        return new Builder();
    }

    public String collection;              // null falls back to the implementation's default collection
    public Embedding embedding;
    public Integer topK = 5;
    public Double threshold = 0d;          // applied inside implementations: score >= threshold for COSINE/IP
    public String filter;                  // backend native filter string, e.g. Milvus boolean expression
    public String vectorField = "vector";
    public List<String> outputFields;      // null means only primary key and score are returned
    public boolean includeVector = false;  // whether to read the vector field back into Document.embedding

    @Deprecated
    public String queryField = "query";    // legacy: treated as outputFields containing queryField
    @Deprecated
    public List<String> extraFields;       // legacy: merged into outputFields
    @Deprecated
    public Expression expression;          // empty-shell DSL, to be decided in P2
    @Deprecated
    public Integer trunkSize = 1000;       // collection creation parameter, moved to CollectionSpec
    @Deprecated
    public Integer dimension = 1536;       // collection creation parameter, moved to CollectionSpec

    public List<String> effectiveOutputFields() {
        if (outputFields != null) return outputFields;
        var fields = new ArrayList<String>();
        if (extraFields != null) fields.addAll(extraFields);
        fields.add(queryField);
        return fields;
    }

    public static class Builder {
        private String collection;
        private Embedding embedding;
        private Integer topK = 5;
        private Double threshold = 0d;
        private String filter;
        private String vectorField = "vector";
        private List<String> outputFields;
        private Boolean includeVector = Boolean.FALSE;

        private String queryField = "query";
        private List<String> extraFields;
        @Deprecated
        private Expression expression;
        private Integer trunkSize = 1000;
        private Integer dimension = 1536;

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder embedding(Embedding embedding) {
            this.embedding = embedding;
            return this;
        }

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public Builder threshold(Double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder filter(String filter) {
            this.filter = filter;
            return this;
        }

        public Builder vectorField(String vectorField) {
            this.vectorField = vectorField;
            return this;
        }

        public Builder outputFields(List<String> outputFields) {
            this.outputFields = outputFields;
            return this;
        }

        public Builder includeVector(Boolean includeVector) {
            this.includeVector = includeVector;
            return this;
        }

        @Deprecated
        public Builder queryField(String queryField) {
            this.queryField = queryField;
            return this;
        }

        @Deprecated
        public Builder extraFields(List<String> extraFields) {
            this.extraFields = extraFields;
            return this;
        }

        @Deprecated
        public Builder expression(Expression expression) {
            this.expression = expression;
            return this;
        }

        @Deprecated
        public Builder trunkSize(Integer trunkSize) {
            this.trunkSize = trunkSize;
            return this;
        }

        @Deprecated
        public Builder dimension(Integer dimension) {
            this.dimension = dimension;
            return this;
        }

        public SimilaritySearchRequest build() {
            var request = new SimilaritySearchRequest();
            request.collection = this.collection;
            request.embedding = this.embedding;
            request.topK = this.topK;
            request.threshold = this.threshold;
            request.filter = this.filter;
            request.vectorField = this.vectorField;
            request.outputFields = this.outputFields;
            request.includeVector = Boolean.TRUE.equals(this.includeVector);
            request.queryField = this.queryField;
            request.extraFields = this.extraFields;
            request.expression = this.expression;
            request.trunkSize = this.trunkSize;
            request.dimension = this.dimension;
            return request;
        }
    }
}
