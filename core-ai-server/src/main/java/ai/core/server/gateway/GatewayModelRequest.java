package ai.core.server.gateway;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Set;

public class GatewayModelRequest {
    @JsonIgnore
    public Set<String> fields = Set.of();

    public String modelId;
    public String displayName;
    public String providerId;
    public String upstreamModel;
    public List<String> endpointTypes;
    public Boolean enabled;
    public Boolean isDefault;
    public Long priority;
    public Long contextWindow;
    public Boolean supportsStream;
    public Boolean supportsTools;
    public Boolean supportsVision;
    public Boolean supportsVideo;
    public List<String> reasoningEfforts;
    public Long maxVideoBytes;
    public Long maxVideoSeconds;
    public Double inputPricePer1MTokens;
    public Double outputPricePer1MTokens;

    boolean hasField(String field) {
        return fields.contains(field);
    }
}
