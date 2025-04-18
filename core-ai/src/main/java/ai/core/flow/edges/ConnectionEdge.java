package ai.core.flow.edges;

import ai.core.flow.FlowEdge;
import ai.core.flow.FlowEdgeType;
import core.framework.json.JSON;

/**
 * @author stephen
 */
public class ConnectionEdge extends FlowEdge<ConnectionEdge> {
    private String value;

    public ConnectionEdge() {

    }

    public ConnectionEdge(String id) {
        super(id, "Connection", FlowEdgeType.FLOW, ConnectionEdge.class);
    }

    public ConnectionEdge(String id, String value) {
        super(id, "Connection", FlowEdgeType.FLOW, ConnectionEdge.class);
        this.value = value;
    }

    @Override
    public void check() {

    }

    public String getValue() {
        return value;
    }

    @Override
    public String serialization(ConnectionEdge edge) {
        return JSON.toJSON(new Domain().from(this));
    }

    @Override
    public void deserialization(ConnectionEdge edge, String c) {
        JSON.fromJSON(Domain.class, c).setup(edge);
    }

    public static class Domain extends FlowEdge.Domain<Domain> {
        @Override
        public Domain from(FlowEdge<?> node) {
            this.fromBase(node);
            return this;
        }

        @Override
        public void setup(FlowEdge<?> node) {
            this.setupBase(node);
        }
    }
}
