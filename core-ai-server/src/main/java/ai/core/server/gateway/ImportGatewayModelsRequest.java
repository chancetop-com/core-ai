package ai.core.server.gateway;

import java.util.List;

public class ImportGatewayModelsRequest {
    public List<Model> models;

    public static class Model {
        public String upstreamModel;
        public String alias;
        public Boolean enabled;
        public Long priority;
    }
}
