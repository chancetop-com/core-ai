package ai.core.flow.edges;

import ai.core.flow.FlowEdge;
import ai.core.flow.FlowEdgeType;
import core.framework.json.JSON;

/**
 * @author stephen
 */
public class SettingEdge extends FlowEdge<SettingEdge> {

    public SettingEdge() {

    }

    public SettingEdge(String id) {
        super(id, "Setting", FlowEdgeType.SETTING, SettingEdge.class);
    }

    @Override
    public void check() {

    }

    @Override
    public String serialization(SettingEdge edge) {
        return JSON.toJSON(new Domain().from(this));
    }

    @Override
    public void deserialization(SettingEdge edge, String c) {
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
