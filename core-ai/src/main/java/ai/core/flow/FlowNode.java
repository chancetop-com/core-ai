package ai.core.flow;

import ai.core.flow.edges.ConnectionEdge;
import ai.core.persistence.Persistence;
import ai.core.utils.ClassUtil;
import core.framework.web.exception.NotFoundException;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public abstract class FlowNode<T extends FlowNode<T>> implements Persistence<T> {
    String id;
    FlowNodeType type;
    String typeName;
    String typeDescription;
    String name;
    String parameterJson;
    FlowNodePosition position;
    Boolean initialized;

    public FlowNode() {

    }

    public FlowNode(String id, String name, String typeName, String typeDescription, FlowNodeType type, Class<?> cls) {
        this.id = id;
        this.typeName = typeName;
        this.typeDescription = typeDescription;
        this.name = name;
        this.type = type;
        this.position = new FlowNodePosition(0, 0);
        this.initialized = false;
        ClassUtil.checkNoArgConstructor(cls);
    }

    public abstract FlowNodeResult execute(String input, Map<String, Object> variables);

    public abstract void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges);

    public abstract void check(List<FlowNode<?>> settings);

    public void initialize(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {
        if (getInitialized()) return;
        init(settings, edges);
        setInitialized(true);
    }

    public FlowNode<?> selectNextNodeByEdgeValue(FlowNodeResult rst, Map<FlowEdge<?>, FlowNode<?>> candidates) {
        if (rst.text() == null) throw new NotFoundException("Cannot find next flow node by edges, previous output is null");
        FlowNode<?> nextNode = null;
        for (var entry : candidates.entrySet()) {
            if (!(entry.getKey() instanceof ConnectionEdge)) continue;
            if (rst.text().equalsIgnoreCase(((ConnectionEdge) entry.getKey()).getValue())) {
                nextNode = entry.getValue();
            }
        }
        if (nextNode == null) throw new NotFoundException("Cannot find next flow node by edges, value: " + rst.text());
        return nextNode;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public FlowNodeType getType() {
        return type;
    }

    public void setType(FlowNodeType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTypeDescription() {
        return typeDescription;
    }

    public void setTypeDescription(String typeDescription) {
        this.typeDescription = typeDescription;
    }

    public FlowNodePosition getPosition() {
        return position;
    }

    public void setPosition(FlowNodePosition position) {
        this.position = position;
    }

    public String getParameterJson() {
        return parameterJson;
    }

    public void setParameterJson(String parameterJson) {
        this.parameterJson = parameterJson;
    }

    public Boolean getInitialized() {
        return initialized;
    }

    public void setInitialized(Boolean initialized) {
        this.initialized = initialized;
    }

    @SuppressWarnings("unchecked")
    public String serialization() {
        return this.serialization((T) this);
    }

    @SuppressWarnings("unchecked")
    public void deserialization(String text) {
        this.deserialization((T) this, text);
    }

    public abstract static class Domain<T extends Domain<T>> {
        public String id;
        public FlowNodeType type;
        public String typeName;
        public String typeDescription;
        public String name;
        public FlowNodePosition position;

        public void fromBase(FlowNode<?> node) {
            this.id = node.getId();
            this.type = node.getType();
            this.typeName = node.getTypeName();
            this.typeDescription = node.getTypeDescription();
            this.name = node.getName();
            this.position = node.getPosition();
        }

        public void setupNodeBase(FlowNode<?> node) {
            node.id = this.id;
            node.type = this.type;
            node.typeName = this.typeName;
            node.typeDescription = this.typeDescription;
            node.name = this.name;
            node.position = this.position;
        }

        public abstract T from(FlowNode<?> node);

        public abstract void setupNode(FlowNode<?> node);
    }
}
