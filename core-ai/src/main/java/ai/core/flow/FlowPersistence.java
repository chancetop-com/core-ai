package ai.core.flow;

import ai.core.persistence.Persistence;
import ai.core.utils.ClassUtil;
import core.framework.api.json.Property;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class FlowPersistence implements Persistence<Flow> {
    @Override
    public String serialization(Flow flow) {
        return JSON.toJSON(new FlowPersistenceDomain().from(flow));
    }

    @Override
    public void deserialization(Flow flow, String c) {
        var domain = JSON.fromJSON(FlowPersistenceDomain.class, c);
        flow.id = domain.id;
        flow.name = domain.name;
        flow.description = domain.description;
        flow.currentNodeId = domain.currentNodeId;
        flow.currentInput = domain.currentInput;
        flow.currentVariables = domain.currentVariables;
        flow.nodes = domain.nodes.stream().<FlowNode<?>>map(v -> {
            var node = (FlowNode<?>) ClassUtil.newByName(v.className);
            node.deserialization(v.text);
            return node;
        }).toList();
        flow.edges = domain.edges.stream().<FlowEdge<?>>map(v -> {
            var edge = (FlowEdge<?>) ClassUtil.newByName(v.className);
            edge.deserialization(v.text);
            return edge;
        }).toList();
    }

    public static class FlowPersistenceDomain {
        @Property(name = "id")
        public String id;
        @Property(name = "name")
        public String name;
        @Property(name = "description")
        public String description;
        @Property(name = "nodes")
        public List<TypeClass> nodes;
        @Property(name = "edges")
        public List<TypeClass> edges;
        @Property(name = "current_node_id")
        public String currentNodeId;
        @Property(name = "current_input")
        public String currentInput;
        @Property(name = "current_variables")
        public Map<String, Object> currentVariables;

        public FlowPersistenceDomain from(Flow flow) {
            if (flow.id == null) throw new IllegalArgumentException("flow id cannot be null");
            this.id = flow.id;
            this.name = flow.name;
            this.description = flow.description;
            this.currentNodeId = flow.currentNodeId;
            this.currentInput = flow.currentInput;
            this.currentVariables = flow.currentVariables;
            this.nodes = flow.nodes.stream().map(v -> {
                var node = new TypeClass();
                node.className = v.getClass().getName();
                node.text = v.serialization();
                return node;
            }).toList();
            this.edges = flow.edges.stream().map(v -> {
                var edge = new TypeClass();
                edge.className = v.getClass().getName();
                edge.text = v.serialization();
                return edge;
            }).toList();
            return this;
        }
    }

    public static class TypeClass {
        @Property(name = "class_name")
        public String className;
        @Property(name = "text")
        public String text;
    }
}
