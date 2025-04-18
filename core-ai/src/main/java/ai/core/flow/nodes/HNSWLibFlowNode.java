package ai.core.flow.nodes;

import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.rag.VectorStoreType;
import ai.core.rag.VectorStores;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class HNSWLibFlowNode extends RagFlowNode<HNSWLibFlowNode> {
    VectorStores vectorStores;

    public HNSWLibFlowNode() {

    }

    public HNSWLibFlowNode(String id, String name, VectorStores vectorStores) {
        super(id, name, "HNSWLib", "HNSWLib RAG", HNSWLibFlowNode.class);
        this.vectorStores = vectorStores;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings) {
        if (!getInitialized()) setVectorStore(vectorStores.getVectorStore(VectorStoreType.HNSW_LIB));
        setInitialized(true);
    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(RagFlowNode<HNSWLibFlowNode> node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(RagFlowNode<HNSWLibFlowNode> node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends RagFlowNode.Domain<Domain> {
        @Override
        public Domain from(RagFlowNode<?> node) {
            this.fromRagBase(node);
            return this;
        }

        @Override
        public void setupNode(RagFlowNode<?> node) {
            this.setupRagNodeBase(node);
        }
    }
}
