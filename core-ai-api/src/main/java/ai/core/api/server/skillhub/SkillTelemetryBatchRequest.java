package ai.core.api.server.skillhub;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * Request body for POST /api/skill-hub/telemetry/batch.
 *
 * @author stephen
 */
public class SkillTelemetryBatchRequest {
    @NotNull
    @Property(name = "batch_id")
    public String batchId;

    @NotNull
    @Property(name = "items")
    public List<SkillTelemetryBatchItemRequest> items;
}
