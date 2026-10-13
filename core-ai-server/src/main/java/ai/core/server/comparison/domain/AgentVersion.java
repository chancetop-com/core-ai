package ai.core.server.comparison.domain;

import ai.core.server.domain.AgentPublishedConfig;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * A saved candidate configuration ("version") of an agent: a full executable config snapshot that can
 * be edited freely, selected as a side of an experiment, and adopted after it wins.
 *
 * @author stephen
 */
@Collection(name = "agent_versions")
public class AgentVersion {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "agent_name")
    public String agentName;

    @Field(name = "label")
    public String label;

    @Field(name = "config")
    public AgentPublishedConfig config;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
