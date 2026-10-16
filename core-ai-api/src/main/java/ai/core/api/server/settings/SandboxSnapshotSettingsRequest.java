package ai.core.api.server.settings;

import core.framework.api.json.Property;
import core.framework.api.validate.Max;
import core.framework.api.validate.Min;
import core.framework.api.validate.NotNull;

import java.math.BigDecimal;

/**
 * @author xander
 */
public class SandboxSnapshotSettingsRequest {
    @NotNull
    @Min(1)
    @Max(3650)
    @Property(name = "retention_days")
    public BigDecimal retentionDays;
}
