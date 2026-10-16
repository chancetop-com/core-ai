package ai.core.api.server.settings;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;

/**
 * @author xander
 */
public class SandboxSnapshotSettingsView {
    @NotNull
    @Property(name = "retention_days")
    public Integer retentionDays;

    @Property(name = "updated_by")
    public String updatedBy;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
