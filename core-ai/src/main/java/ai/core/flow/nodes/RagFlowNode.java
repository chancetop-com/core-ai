package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeType;
import ai.core.rag.RagConfig;
import ai.core.rag.VectorStore;

/**
 * @author stephen
 */
public abstract class RagFlowNode<T extends RagFlowNode<T>> extends FlowNode<RagFlowNode<T>> {
    private Integer topK = 5;
    private Double threshold = 0d;
    private RagConfig ragConfig;
    private VectorStore vectorStore;

    public RagFlowNode() {

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

    public abstract static class Domain<T extends Domain<T>> extends FlowNode.Domain<Domain<T>> {
        public Integer topK = 5;
        public Double threshold = 0d;

        public void fromRagBase(RagFlowNode<?> node) {
            this.topK = node.topK;
            this.threshold = node.threshold;
            from((FlowNode<?>) node);
        }

        public void setupRagNodeBase(RagFlowNode<?> node) {
            node.topK = this.topK;
            node.threshold = this.threshold;
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
