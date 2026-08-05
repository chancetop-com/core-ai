package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Provenance for an Agent config frozen into a workflow version.
 *
 * @author Xander
 */
public class AgentSnapshotSource {
    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "source_kind")
    public String sourceKind;

    @Field(name = "source_updated_at")
    public ZonedDateTime sourceUpdatedAt;

    @Field(name = "captured_at")
    public ZonedDateTime capturedAt;
}
