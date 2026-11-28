package ai.core.api.server.skillhub;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Response body for POST /api/skill-hub/telemetry/batch.
 *
 * @author stephen
 */
public class SkillTelemetryBatchResponse {
    @NotNull
    @Property(name = "accepted")
    public Boolean accepted;

    @NotNull
    @Property(name = "duplicate")
    public Boolean duplicate;

    @NotNull
    @Property(name = "recorded_items")
    public Integer recordedItems;

    @NotNull
    @Property(name = "dropped_unknown_items")
    public Integer droppedUnknownItems;
}
