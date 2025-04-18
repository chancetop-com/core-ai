package ai.core.flow.nodes;

import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.rag.RagConfig;
import ai.core.rag.VectorStoreType;
import ai.core.rag.VectorStores;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class MilvusFlowNode extends RagFlowNode<MilvusFlowNode> {
    VectorStores vectorStores;

    public MilvusFlowNode() {

    }

    public MilvusFlowNode(String id, String name, VectorStores vectorStores) {
        super(id, name, "Milvus", "Milvus RAG", MilvusFlowNode.class);
        this.vectorStores = vectorStores;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        if (!getInitialized()) {
            setVectorStore(vectorStores.getVectorStore(VectorStoreType.MILVUS));
            setRagConfig(RagConfig.builder().useRag(true).topK(getTopK()).threshold(getThreshold()).vectorStore(getVectorStore()).build());
        }
        setInitialized(true);
    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(RagFlowNode<MilvusFlowNode> node) {
        return JSON.toJSON(new Domain().from(node));
    }

    @Override
    public void deserialization(RagFlowNode<MilvusFlowNode> node, String c) {
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
