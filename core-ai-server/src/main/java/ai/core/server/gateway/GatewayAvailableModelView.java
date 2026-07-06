package ai.core.server.gateway;

import java.util.List;

public class GatewayAvailableModelView {
    public String modelId;
    public String displayName;
    public String providerName;
    public List<String> endpointTypes;
    public Boolean supportsVision;
}
