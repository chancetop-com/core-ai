package ai.core.api.server.gateway;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class GatewayModelRequest {
    /**
     * Internal bookkeeping of which fields were present in the request JSON,
     * populated by the web service implementation from the raw body.
     * Never sent by clients.
     */
    @NotNull
    @Property(name = "fields")
    public Map<String, Boolean> fields = Map.of();

    @Property(name = "modelId")
    public String modelId;

    @Property(name = "displayName")
    public String displayName;

    @Property(name = "providerId")
    public String providerId;

    @Property(name = "upstreamModel")
    public String upstreamModel;

    @Property(name = "endpointTypes")
    public List<String> endpointTypes;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "isDefault")
    public Boolean isDefault;

    @Property(name = "priority")
    public Long priority;

    @Property(name = "contextWindow")
    public Long contextWindow;

    @Property(name = "supportsStream")
    public Boolean supportsStream;

    @Property(name = "supportsTools")
    public Boolean supportsTools;

    @Property(name = "supportsVision")
    public Boolean supportsVision;

    @Property(name = "supportsVideo")
    public Boolean supportsVideo;

    @Property(name = "supportsFile")
    public Boolean supportsFile;

    @Property(name = "responseFormat")
    public String responseFormat;

    @Property(name = "reasoningEfforts")
    public List<String> reasoningEfforts;

    @Property(name = "supportsReasoningEffort")
    public Boolean supportsReasoningEffort;

    @Property(name = "maxVideoBytes")
    public Long maxVideoBytes;

    @Property(name = "maxVideoSeconds")
    public Long maxVideoSeconds;

    @Property(name = "inputPricePer1MTokens")
    public Double inputPricePer1MTokens;

    @Property(name = "outputPricePer1MTokens")
    public Double outputPricePer1MTokens;

    @Property(name = "cacheReadInputPricePer1MTokens")
    public Double cacheReadInputPricePer1MTokens;

    @Property(name = "peakPriceMultiplier")
    public Double peakPriceMultiplier;

    public boolean hasField(String field) {
        return fields.containsKey(field);
    }
}
