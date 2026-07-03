package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

@Collection(name = "gateway_model")
public class GatewayModelConfig {
    @Id
    public String id;

    @Field(name = "model_id")
    public String modelId;

    @Field(name = "display_name")
    public String displayName;

    @Field(name = "provider_id")
    public String providerId;

    @Field(name = "upstream_model")
    public String upstreamModel;

    @Field(name = "endpoint_types")
    public List<String> endpointTypes;

    @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "priority")
    public Long priority;

    @Field(name = "context_window")
    public Long contextWindow;

    @Field(name = "supports_stream")
    public Boolean supportsStream;

    @Field(name = "supports_tools")
    public Boolean supportsTools;

    @Field(name = "supports_vision")
    public Boolean supportsVision;

    @Field(name = "input_price_per_1m_tokens")
    public Double inputPricePer1MTokens;

    @Field(name = "output_price_per_1m_tokens")
    public Double outputPricePer1MTokens;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "updated_by")
    public String updatedBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
