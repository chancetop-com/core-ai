package ai.core.flow;

import ai.core.persistence.Persistence;
import ai.core.utils.ClassUtil;

/**
 * @author stephen
 */
public abstract class FlowEdge<T extends FlowEdge<T>> implements Persistence<T> {
    String id;
    String name;
    FlowEdgeType type;
    String sourceNodeId;
    String targetNodeId;

    public FlowEdge() {

    }

    public FlowEdge(String id, String name, FlowEdgeType type, Class<?> cls) {
        this.id = id;
        this.name = name;
        this.type = type;
        ClassUtil.checkNoArgConstructor(cls);
    }

    public abstract void check();

    public void connect(String sourceNodeId, String targetNodeId) {
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public FlowEdgeType getType() {
        return type;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    @SuppressWarnings("unchecked")
    public String serialization() {
        return this.serialization((T) this);
    }

    @SuppressWarnings("unchecked")
    public void deserialization(String text) {
        this.deserialization((T) this, text);
    }

    public abstract static class Domain<T extends FlowEdge.Domain<T>> {
        public String id;
        public String name;
        public FlowEdgeType type;
        public String sourceNodeId;
        public String targetNodeId;

        public void fromBase(FlowEdge<?> edge) {
            this.id = edge.id;
            this.type = edge.type;
            this.sourceNodeId = edge.sourceNodeId;
            this.targetNodeId = edge.targetNodeId;
            this.name = edge.name;
        }

        public void setupBase(FlowEdge<?> edge) {
            edge.id = this.id;
            edge.type = this.type;
            edge.sourceNodeId = this.sourceNodeId;
            edge.targetNodeId = this.targetNodeId;
            edge.name = this.name;
        }

        public abstract T from(FlowEdge<?> node);

        public abstract void setup(FlowEdge<?> node);
    }
}
