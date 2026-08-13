package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ImportGatewayModelsRequest {
    @Property(name = "models")
    public List<Model> models;

    public static class Model {
        @Property(name = "upstreamModel")
        public String upstreamModel;

        @Property(name = "alias")
        public String alias;

        @Property(name = "enabled")
        public Boolean enabled;

        @Property(name = "priority")
        public Long priority;
    }
}
