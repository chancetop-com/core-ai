package ai.core.api.server.replay;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Lightweight run row used by the experiment detail response (no request payload).
 *
 * @author stephen
 */
public class ReplayRunSummaryView {
    @Property(name = "id")
    public String id;

    @Property(name = "label")
    public String label;

    @Property(name = "status")
    public String status;

    @Property(name = "sample_count")
    public Integer sampleCount;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
