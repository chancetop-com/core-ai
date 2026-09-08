package ai.core.vectorstore.request;

import java.util.List;

/**
 * Scalar (non-vector) query with pagination, corresponding to Milvus QueryReq.
 *
 * @author stephen
 */
public class ScalarQueryRequest {
    public static Builder builder() {
        return new Builder();
    }

    public String collection;              // null falls back to the implementation's default collection
    public String filter;                  // backend native filter string, required
    public Integer offset = 0;
    public Integer limit = 10;
    public List<String> outputFields;      // null means only primary key is returned
    public boolean includeVector = false;

    public static class Builder {
        private String collection;
        private String filter;
        private Integer offset = 0;
        private Integer limit = 10;
        private List<String> outputFields;
        private Boolean includeVector = Boolean.FALSE;

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder filter(String filter) {
            this.filter = filter;
            return this;
        }

        public Builder offset(Integer offset) {
            this.offset = offset;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
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

        public ScalarQueryRequest build() {
            var request = new ScalarQueryRequest();
            request.collection = this.collection;
            request.filter = this.filter;
            request.offset = this.offset;
            request.limit = this.limit;
            request.outputFields = this.outputFields;
            request.includeVector = Boolean.TRUE.equals(this.includeVector);
            return request;
        }
    }
}
