package ai.core.server.gateway;

import java.util.List;

public class GatewayDiscoveredModelView {
    public String id;
    public String displayName;
    public List<String> endpointTypes;
    public Long contextWindow;
    public Boolean supportsStream;
    public Boolean supportsTools;
    public Boolean supportsVision;
    public Double inputPricePer1MTokens;
    public Double outputPricePer1MTokens;
    public Boolean imported;
}
