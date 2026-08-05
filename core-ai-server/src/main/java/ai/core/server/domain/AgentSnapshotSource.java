package ai.core.server.domain;

import core.framework.api.json.Property;
import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Provenance for an Agent config frozen into a workflow version.
 *
 * @author Xander
 */
public class AgentSnapshotSource {
    @Property(name = "agent_id")
    @Field(name = "agent_id")
    public String agentId;

    @Property(name = "source_kind")
    @Field(name = "source_kind")
    public String sourceKind;

    @Property(name = "source_updated_at")
    @Field(name = "source_updated_at")
    public ZonedDateTime sourceUpdatedAt;

    @Property(name = "captured_at")
    @Field(name = "captured_at")
    public ZonedDateTime capturedAt;
}
