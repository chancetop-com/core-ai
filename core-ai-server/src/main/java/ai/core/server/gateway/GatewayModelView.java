package ai.core.server.gateway;

import java.time.ZonedDateTime;
import java.util.List;

public class GatewayModelView {
    public String id;
    public String modelId;
    public String displayName;
    public String providerId;
    public String providerName;
    public String upstreamModel;
    public List<String> endpointTypes;
    public Boolean enabled;
    public Boolean isDefault;
    public Long priority;
    public Long contextWindow;
    public Boolean supportsStream;
    public Boolean supportsTools;
    public Boolean supportsVision;
    public Double inputPricePer1MTokens;
    public Double outputPricePer1MTokens;
    public String createdBy;
    public String updatedBy;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
}
