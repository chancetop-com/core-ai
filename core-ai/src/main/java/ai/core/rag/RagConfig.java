package ai.core.rag;

import core.framework.util.Strings;

/**
 * @author stephen
 */
public class RagConfig {
    public static final String AGENT_RAG_CONTEXT_PLACEHOLDER = "__rag_default_context_placeholder__";
    public static final String AGENT_RAG_CONTEXT_TEMPLATE = Strings.format("\nContext:{{{}}}\n\n", AGENT_RAG_CONTEXT_PLACEHOLDER);

    public static Builder builder() {
        return new Builder();
    }

    boolean useRag = false;
    Integer topK = 5;
    Double threshold = 0d;
    VectorStore vectorStore;
    String collection;

    public boolean useRag() {
        return useRag;
    }

    public Integer topK() {
        return topK;
    }

    public Double threshold() {
        return threshold;
    }

    public VectorStore vectorStore() {
        return vectorStore;
    }

    public String collection() {
        return collection;
    }

    public static class Builder {
        private boolean useRag = false;
        private Integer topK = 5;
        private Double threshold = 0d;
        private VectorStore vectorStore;
        private String collection;

        public Builder collection(String collection) {
            this.collection = collection;
            return this;
        }

        public Builder useRag(Boolean useRag) {
            this.useRag = useRag;
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

        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public RagConfig build() {
            var conf = new RagConfig();
            conf.useRag = this.useRag;
            conf.topK = this.topK;
            conf.threshold = this.threshold;
            conf.vectorStore = this.vectorStore;
            conf.collection = this.collection;
            return conf;
        }
    }
}
