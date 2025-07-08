package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeType;
import ai.core.rag.RagConfig;
import ai.core.vectorstore.VectorStore;
import ai.core.vectorstore.VectorStores;
import core.framework.api.json.Property;

/**
 * @author stephen
 */
public abstract class RagFlowNode<T extends RagFlowNode<T>> extends FlowNode<RagFlowNode<T>> {
    private Integer topK = 5;
    private Double threshold = 0d;
    private RagConfig ragConfig;
    private VectorStore vectorStore;
    private VectorStores vectorStores;

    public RagFlowNode(String typeName, String typeDescription, Class<?> cls) {
        super(typeName, typeDescription, FlowNodeType.RAG, cls);
    }

    public RagFlowNode(String id, String name, String typeName, String typeDescription, Class<?> cls) {
        super(id, name, typeName, typeDescription, FlowNodeType.RAG, cls);
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public RagConfig getRagConfig() {
        return ragConfig;
    }

    public void setRagConfig(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
    }

    public VectorStore getVectorStore() {
        return vectorStore;
    }

    public void setVectorStore(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public VectorStores getVectorStores() {
        return vectorStores;
    }

    public void setVectorStores(VectorStores vectorStores) {
        this.vectorStores = vectorStores;
    }

    public abstract static class Domain<T extends Domain<T>> extends FlowNode.Domain<Domain<T>> {
        @Property(name = "topK")
        public Integer topK = 5;
        @Property(name = "threshold")
        public Double threshold = 0d;

        public void fromRagBase(RagFlowNode<?> node) {
            this.topK = node.getTopK();
            this.threshold = node.getThreshold();
            from((FlowNode<?>) node);
        }

        public void setupRagNodeBase(RagFlowNode<?> node) {
            node.setTopK(this.topK);
            node.setThreshold(this.threshold);
            setupNode((FlowNode<?>) node);
        }

        @Override
        public T from(FlowNode<?> node) {
            this.fromBase(node);
            return null;
        }

        public abstract T from(RagFlowNode<?> node);

        @Override
        public void setupNode(FlowNode<?> node) {
            setupNodeBase(node);
        }

        public abstract void setupNode(RagFlowNode<?> node);
    }
}
