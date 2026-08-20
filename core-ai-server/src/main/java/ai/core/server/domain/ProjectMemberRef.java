package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * A member (agent or workflow) attached to a project. Membership lives on the PROJECT side —
 * agent/workflow definitions carry no project association (shared resources, loose organization).
 * The name is snapshotted at attach time so display survives removal of the member.
 *
 * @author stephen
 */
public class ProjectMemberRef {
    @Field(name = "type")
    public String type;   // agent | workflow

    @Field(name = "id")
    public String id;

    @Field(name = "name")
    public String name;
}
