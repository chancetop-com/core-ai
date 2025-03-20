package ai.core.rag;

import ai.core.document.Embedding;
import ai.core.rag.filter.Expression;

import java.util.List;

/**
 * @author stephen
 */
public class SimilaritySearchRequest {
    public static Builder builder() {
        return new Builder();
    }

    public Integer topK = 5;
    public Integer trunkSize = 1000; // ~200 words
    public Integer dimension = 1536; // text-embeddings-ada-002's dimension
    public String queryField = "query";
    public String vectorField = "vector";
    public String collection;
    public Double threshold = 0d;
    public Expression expression;
    public Embedding embedding;
    public List<String> extraFields;

    public static class Builder {
        private Integer topK = 5;
        private Integer trunkSize = 1000; // ~200 words
        private Integer dimension = 1536; // text-embeddings-ada-002's dimension
        private String queryField = "query";
        private String vectorField = "vector";
        private String collection;
        private Double threshold = 0d;
        private Expression expression;
        private Embedding embedding;
        private List<String> extraFields;

        public Builder topK(Integer topK) {
            this.topK = topK;
            return this;
        }

        public Builder trunkSize(Integer trunkSize) {
            this.trunkSize = trunkSize;
            return this;
        }

        public Builder dimension(Integer dimension) {
            this.dimension = dimension;
            return this;
        }

        public Builder vectorField(String vectorField) {
            this.vectorField = vectorField;
            return this;
        }

        public Builder queryField(String queryField) {
            this.queryField = queryField;
            return this;
        }

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder threshold(Double threshold) {
            this.threshold = threshold;
            return this;
        }

        public Builder extraFields(List<String> extraFields) {
            this.extraFields = extraFields;
            return this;
        }

        public Builder embedding(Embedding embedding) {
            this.embedding = embedding;
            return this;
        }

        public Builder expression(Expression expression) {
            this.expression = expression;
            return this;
        }

        public SimilaritySearchRequest build() {
            var req = new SimilaritySearchRequest();
            req.topK = this.topK;
            req.trunkSize = this.trunkSize;
            req.dimension = this.dimension;
            req.queryField = this.queryField;
            req.vectorField = this.vectorField;
            req.collection = this.collection;
            req.threshold = this.threshold;
            req.expression = this.expression;
            req.embedding = this.embedding;
            req.extraFields = this.extraFields;
            return req;
        }
    }
}
